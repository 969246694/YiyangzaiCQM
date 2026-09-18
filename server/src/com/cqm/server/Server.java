package com.cqm.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端：接收探针上报的运行期指标，并提供静态分析、规则检测、质量评分、
 * 告警与报告导出能力，同时对外暴露可视化页面。
 *
 * <p>对应论文第 4、5 章的服务端模块。技术选型说明：为便于单机演示，
 * HTTP 层使用 JDK 内置的 HttpServer，持久化使用 H2 嵌入式数据库
 * （表结构与论文表 4-3 一致）；工程化部署时可替换为 Spring Boot + MySQL。
 *
 * <p>接口一览：
 * <pre>
 *   POST /api/metrics     探针上报运行期指标
 *   GET  /api/report      导出 HTML 质量报告
 *   GET  /api/analyze     触发静态代码分析（?path=源码目录）
 *   GET  /api/overview    汇总数据（页面使用）
 *   GET  /api/table       运行期指标明细
 *   GET  /api/static      静态指标明细
 *   GET  /api/violations  违规记录
 *   GET  /api/score       质量评分明细
 *   GET  /api/alarms      告警记录
 *   GET  /api/stats       汇总计数
 *   GET  /                可视化页面
 * </pre>
 */
public final class Server {

    private static final Pattern METRIC = Pattern.compile(
            "\\{\"className\":\"([^\"]*)\",\"methodName\":\"([^\"]*)\",\"calls\":(\\d+),"
                    + "\"totalNanos\":(\\d+),\"errors\":(\\d+),\"maxNanos\":(\\d+),"
                    + "\"minNanos\":(\\d+),\"avgMillis\":([0-9.]+)}");

    /** 方法级累计运行期指标。 */
    static final class Agg {
        final String className;
        final String methodName;
        final LongAdder calls = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final LongAdder errors = new LongAdder();
        volatile long maxNanos;
        volatile long minNanos = Long.MAX_VALUE;
        volatile long lastSeen;

        Agg(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        void merge(long c, long total, long err, long max, long min) {
            calls.add(c);
            totalNanos.add(total);
            errors.add(err);
            synchronized (this) {
                if (max > maxNanos) {
                    maxNanos = max;
                }
                if (min > 0 && min < minNanos) {
                    minNanos = min;
                }
            }
            lastSeen = System.currentTimeMillis();
        }

        double avgMillis() {
            long c = calls.sum();
            return c == 0 ? 0 : (totalNanos.sum() / 1_000_000d) / c;
        }

        double errRate() {
            long c = calls.sum();
            return c == 0 ? 0 : errors.sum() * 100d / c;
        }

        Models.Metric toModel() {
            return new Models.Metric(className, methodName, calls.sum(), avgMillis(),
                    maxNanos / 1_000_000d,
                    minNanos == Long.MAX_VALUE ? 0 : minNanos / 1_000_000d,
                    errors.sum(), errRate());
        }
    }

    private static final Map<String, Agg> STORE = new ConcurrentHashMap<>();
    private static final LongAdder REPORT_COUNT = new LongAdder();
    private static final LongAdder REPORT_BYTES = new LongAdder();
    private static volatile long lastReportAt = 0L;
    private static volatile String appName = "-";
    private static volatile int instrumentedCount = 0;

    private static volatile int projectId = 1;
    private static volatile String sourcePath = System.getProperty("cqm.src", "demo/src");
    private static volatile Models.Score lastScore = null;
    private static volatile int lastViolationCount = 0;
    private static volatile String lastReportPath = "-";

    // ─────────────────── 安全控制 ───────────────────
    /**
     * 写操作的访问令牌。为空表示不校验（便于本地开发）；
     * 公网部署时必须通过 {@code -Dcqm.token=<随机值>} 配置，
     * 探针侧以相同的 {@code -Dcqm.token} 携带 {@code X-CQM-Token} 请求头发送。
     */
    private static final String TOKEN = System.getProperty("cqm.token", "");

    /**
     * 允许被静态分析的源码根目录（绝对路径）。
     *
     * <p>{@code /api/analyze} 的 {@code path} 参数只允许指向该目录及其子目录，
     * 防止通过构造路径扫描服务器上的任意目录（路径穿越）。
     */
    private static volatile String srcRoot =
            java.nio.file.Paths.get(System.getProperty("cqm.src", "demo/src"))
                    .toAbsolutePath().normalize().toString();

    /** 校验写操作令牌；未配置令牌时放行（本地开发模式）。 */
    private static boolean authorized(HttpExchange ex) {
        if (TOKEN.isEmpty()) {
            return true;
        }
        String got = ex.getRequestHeaders().getFirst("X-CQM-Token");
        return TOKEN.equals(got);
    }

    /** 请求是否来自本机回环地址（用于放行本机探针与运维调用）。 */
    private static boolean fromLoopback(HttpExchange ex) {
        java.net.InetSocketAddress addr = ex.getRemoteAddress();
        if (addr == null || addr.getAddress() == null) {
            return false;
        }
        return addr.getAddress().isLoopbackAddress();
    }

    /** 判断给定路径是否位于源码根目录之内。 */
    private static boolean insideSrcRoot(String candidate) {
        try {
            Path p = Paths.get(candidate).toAbsolutePath().normalize();
            Path root = Paths.get(srcRoot).toAbsolutePath().normalize();
            return p.startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Db.init();
        projectId = Db.ensureProject(System.getProperty("cqm.app", "demo-app"), "com.demo");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/metrics", Server::handleMetrics);
        server.createContext("/api/report", Server::handleReport);
        server.createContext("/api/analyze", Server::handleAnalyze);
        server.createContext("/api/overview", Server::handleOverview);
        server.createContext("/api/table", Server::handleTable);
        server.createContext("/api/static", Server::handleStatic);
        server.createContext("/api/violations", Server::handleViolations);
        server.createContext("/api/score", Server::handleScore);
        server.createContext("/api/alarms", Server::handleAlarms);
        server.createContext("/api/stats", Server::handleStats);
        server.createContext("/", Server::handleIndex);
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.start();
        System.out.println("cqm-server started: http://127.0.0.1:" + port + "/");
        System.out.println("  源码目录(静态分析): "
                + Paths.get(sourcePath).toAbsolutePath().normalize());
        System.out.println("  安全策略: 写操作令牌=" + (TOKEN.isEmpty() ? "未启用（仅限本地开发）"
                : "已启用")
                + "，静态分析路径限制=" + srcRoot);

        // 启动时先做一次静态分析，保证页面打开即有数据
        if (Boolean.parseBoolean(System.getProperty("cqm.autoscan", "true"))) {
            try {
                analyzeAndEvaluate(true);
            } catch (Throwable t) {
                System.err.println("[analyze] 启动扫描失败: " + t);
            }
        }
    }

    // ─────────────────── 静态分析 + 规则 + 评分 ───────────────────
    /** 上次评估时间，用于节流：避免高频上报时反复全量扫描源码。 */
    private static volatile long lastEvalAt = 0L;

    /** 评估节流间隔（毫秒），可通过 -Dcqm.evalInterval 调整。 */
    private static final long EVAL_INTERVAL =
            Long.getLong("cqm.evalInterval", 3000L);

    private static synchronized void analyzeAndEvaluate() {
        analyzeAndEvaluate(false);
    }

    /**
     * 执行一次"静态分析 → 规则检测 → 评分 → 落库"。
     *
     * @param force true 表示强制评估（手动触发接口使用）；
     *              false 表示按 {@link #EVAL_INTERVAL} 节流，
     *              避免探针高频上报时反复扫描源码目录造成无谓的开销
     */
    private static synchronized void analyzeAndEvaluate(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastEvalAt < EVAL_INTERVAL) {
            return;
        }
        lastEvalAt = now;
        Path root = Paths.get(sourcePath).toAbsolutePath().normalize();
        List<Models.StaticMethod> statics = StaticAnalyzer.analyze(root);
        Db.saveStaticMetrics(projectId, statics);

        // 覆盖率：已插桩方法数 vs 有调用记录的方法数
        Models.Coverage cov = new Models.Coverage();
        cov.instrumented = instrumentedCount;
        cov.covered = STORE.size();
        cov.ratio = cov.instrumented == 0 ? 0 : cov.covered * 100d / cov.instrumented;

        List<Models.Metric> metrics = modelsOf(STORE.values());

        List<Models.Violation> violations =
                RuleEngine.check(statics, metrics, StaticAnalyzer.DUP_BLOCKS);
        Db.saveViolations(projectId, violations);
        lastViolationCount = violations.size();

        Models.Score score = ScoreCalculator.score(statics, metrics, violations, cov);
        lastScore = score;
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Db.saveScore(projectId, score.total, today);
        Db.saveCoverage(projectId, cov.instrumented, cov.covered, cov.ratio, today);

        // 阈值告警：异常率超过 5% 的方法
        List<Models.Alarm> alarms = new ArrayList<>();
        for (Models.Metric m : metrics) {
            if (m.errRate > 5) {
                alarms.add(new Models.Alarm("异常率", m.errRate, 5,
                        m.className + "#" + m.methodName + " 异常率 "
                                + String.format("%.2f%%", m.errRate) + "，超过阈值 5%"));
            }
        }
        Db.saveAlarms(projectId, alarms);

        System.out.printf("[analyze] 静态方法 %d 个，运行期方法 %d 个，违规 %d 条，"
                        + "覆盖率 %.1f%%（%d/%d），得分 %.1f（%s）%n",
                statics.size(), metrics.size(), violations.size(), cov.ratio,
                cov.covered, cov.instrumented, score.total, score.grade);
    }

    // ─────────────────── 接收上报 ───────────────────
    private static void handleMetrics(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "method not allowed");
            return;
        }
        // 上报是写操作：公网部署必须携带令牌，防止伪造指标污染数据
        if (!authorized(ex)) {
            System.err.println("[security] 拒绝未授权上报，来源 " + ex.getRemoteAddress());
            respond(ex, 401, "application/json;charset=UTF-8",
                    "{\"error\":\"unauthorized\"}");
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        REPORT_COUNT.increment();
        REPORT_BYTES.add(body.length());
        lastReportAt = System.currentTimeMillis();

        Matcher app = Pattern.compile("\"app\":\"([^\"]*)\"").matcher(body);
        if (app.find()) {
            appName = app.group(1);
        }
        Matcher inst = Pattern.compile("\"instrumented\":(\\d+)").matcher(body);
        if (inst.find()) {
            instrumentedCount = Integer.parseInt(inst.group(1));
        }

        Matcher m = METRIC.matcher(body);
        int n = 0;
        while (m.find()) {
            String key = m.group(1) + "#" + m.group(2);
            Agg agg = STORE.computeIfAbsent(key, k -> new Agg(m.group(1), m.group(2)));
            agg.merge(Long.parseLong(m.group(3)), Long.parseLong(m.group(4)),
                    Long.parseLong(m.group(5)), Long.parseLong(m.group(6)),
                    Long.parseLong(m.group(7)));
            n++;
        }
        // 持久化：运行期指标落库（论文表 4-3 的 metric_raw）
        Db.saveMetrics(projectId, modelsOf(STORE.values()));

        // 每收到一次上报就重新评估一次（含动态规则与评分）
        try {
            analyzeAndEvaluate();
        } catch (Throwable t) {
            System.err.println("[analyze] 评估失败: " + t);
        }
        respond(ex, 200, "application/json", "{\"accepted\":" + n + "}");
    }

    private static List<Models.Metric> modelsOf(java.util.Collection<Agg> aggs) {
        List<Models.Metric> out = new ArrayList<>(aggs.size());
        for (Agg a : aggs) {
            out.add(a.toModel());
        }
        return out;
    }

    // ─────────────────── 查询接口 ───────────────────
    private static void handleTable(HttpExchange ex) throws IOException {
        List<Models.Metric> list = modelsOf(STORE.values());
        list.sort(Comparator.comparingDouble((Models.Metric a) -> a.avgMillis * a.calls).reversed());
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Models.Metric a = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            Agg raw = STORE.get(a.className + "#" + a.methodName);
            sb.append("{\"className\":\"").append(a.className)
                    .append("\",\"methodName\":\"").append(a.methodName)
                    .append("\",\"calls\":").append(a.calls)
                    .append(",\"avgMillis\":").append(String.format("%.4f", a.avgMillis))
                    .append(",\"maxMillis\":").append(String.format("%.4f", a.maxMillis))
                    .append(",\"minMillis\":").append(String.format("%.4f", a.minMillis))
                    .append(",\"errors\":").append(a.errors)
                    .append(",\"errRate\":").append(String.format("%.2f", a.errRate))
                    .append(",\"lastSeen\":\"").append(raw == null || raw.lastSeen == 0 ? "-"
                            : fmt.format(new Date(raw.lastSeen)))
                    .append("\"}");
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        List<Models.StaticMethod> list = Db.staticMetrics(projectId);
        list.sort(Comparator.comparingInt((Models.StaticMethod a) -> a.complexity).reversed());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Models.StaticMethod m = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"className\":\"").append(m.className)
                    .append("\",\"methodName\":\"").append(m.methodName)
                    .append("\",\"loc\":").append(m.loc)
                    .append(",\"complexity\":").append(m.complexity)
                    .append(",\"emptyCatch\":").append(m.emptyCatch)
                    .append(",\"unclosedResource\":").append(m.unclosedResource)
                    .append('}');
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleViolations(HttpExchange ex) throws IOException {
        List<Models.Violation> list = Db.violations(projectId);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Models.Violation v = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"ruleName\":\"").append(esc(v.ruleName))
                    .append("\",\"location\":\"").append(esc(v.location))
                    .append("\",\"detail\":\"").append(esc(v.detail))
                    .append("\",\"level\":\"").append(esc(v.level)).append("\"}");
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleScore(HttpExchange ex) throws IOException {
        Models.Score s = lastScore;
        StringBuilder sb = new StringBuilder("{\"total\":")
                .append(s == null ? 0 : s.total)
                .append(",\"grade\":\"").append(s == null ? "-" : esc(s.grade))
                .append("\",\"items\":[");
        if (s != null) {
            for (int i = 0; i < s.items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(esc(s.items.get(i))).append('"');
            }
        }
        sb.append("]}");
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleAlarms(HttpExchange ex) throws IOException {
        List<Models.Alarm> list = Db.alarms(projectId, 20);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Models.Alarm a = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"metric\":\"").append(esc(a.metric))
                    .append("\",\"value\":").append(String.format("%.2f", a.value))
                    .append(",\"threshold\":").append(a.threshold)
                    .append(",\"detail\":\"").append(esc(a.detail)).append("\"}");
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleOverview(HttpExchange ex) throws IOException {
        Models.Score s = lastScore;
        int[] cov = Db.latestCoverage(projectId);
        List<Models.StaticMethod> statics = Db.staticMetrics(projectId);
        double avgCx = statics.isEmpty() ? 0
                : statics.stream().mapToInt(m -> m.complexity).average().orElse(0);
        Map<String, Integer> byLevel = RuleEngine.countByLevel(Db.violations(projectId));

        String json = "{\"app\":\"" + esc(appName) + "\""
                + ",\"methods\":" + STORE.size()
                + ",\"reports\":" + REPORT_COUNT.sum()
                + ",\"reportBytes\":" + REPORT_BYTES.sum()
                + ",\"lastReportAt\":" + lastReportAt
                + ",\"staticMethods\":" + statics.size()
                + ",\"avgComplexity\":" + String.format("%.2f", avgCx)
                + ",\"score\":" + (s == null ? 0 : s.total)
                + ",\"grade\":\"" + (s == null ? "-" : esc(s.grade)) + "\""
                + ",\"instrumented\":" + cov[0]
                + ",\"covered\":" + cov[1]
                + ",\"coverage\":" + String.format("%.1f", cov[0] == 0 ? 0 : cov[1] * 100d / cov[0])
                + ",\"violations\":" + lastViolationCount
                + ",\"high\":" + byLevel.getOrDefault("高", 0)
                + ",\"mid\":" + byLevel.getOrDefault("中", 0)
                + ",\"reportPath\":\"" + esc(lastReportPath) + "\"}";
        respond(ex, 200, "application/json;charset=UTF-8", json);
    }

    private static void handleStats(HttpExchange ex) throws IOException {
        String json = "{\"app\":\"" + esc(appName) + "\",\"methods\":" + STORE.size()
                + ",\"reports\":" + REPORT_COUNT.sum()
                + ",\"reportBytes\":" + REPORT_BYTES.sum()
                + ",\"lastReportAt\":" + lastReportAt + "}";
        respond(ex, 200, "application/json;charset=UTF-8", json);
    }

    private static void handleReport(HttpExchange ex) throws IOException {
        List<Models.StaticMethod> statics = Db.staticMetrics(projectId);
        List<Models.Metric> metrics = modelsOf(STORE.values());
        List<Models.Violation> violations = Db.violations(projectId);
        int[] cov = Db.latestCoverage(projectId);
        Models.Coverage coverage = new Models.Coverage();
        coverage.instrumented = cov[0];
        coverage.covered = cov[1];
        coverage.ratio = cov[0] == 0 ? 0 : cov[1] * 100d / cov[0];
        Models.Score score = lastScore != null ? lastScore
                : ScoreCalculator.score(statics, metrics, violations, coverage);
        Path out = ReportExporter.export(appName, statics, metrics, violations, score, coverage,
                Paths.get("reports"));
        lastReportPath = out.toAbsolutePath().toString();
        respond(ex, 200, "application/json;charset=UTF-8",
                "{\"ok\":true,\"path\":\"" + esc(lastReportPath) + "\"}");
    }

    private static void handleAnalyze(HttpExchange ex) throws IOException {
        // 触发静态分析属于写操作（会覆盖静态指标与违规记录）
        if (!authorized(ex) && !fromLoopback(ex)) {
            System.err.println("[security] 拒绝未授权分析请求，来源 " + ex.getRemoteAddress());
            respond(ex, 401, "application/json;charset=UTF-8", "{\"error\":\"unauthorized\"}");
            return;
        }
        String q = ex.getRequestURI().getQuery();
        if (q != null && q.startsWith("path=")) {
            String requested = java.net.URLDecoder.decode(q.substring(5), StandardCharsets.UTF_8);
            // 路径穿越防护：只允许扫描配置的源码根目录之内的路径
            if (!insideSrcRoot(requested)) {
                System.err.println("[security] 拒绝越界路径: " + requested
                        + "（允许范围: " + srcRoot + "）");
                respond(ex, 403, "application/json;charset=UTF-8",
                        "{\"error\":\"path out of allowed root\",\"allowed\":\""
                                + esc(srcRoot) + "\"}");
                return;
            }
            sourcePath = requested;
        }
        analyzeAndEvaluate(true);
        respond(ex, 200, "application/json;charset=UTF-8",
                "{\"ok\":true,\"source\":\""
                        + esc(Paths.get(sourcePath).toAbsolutePath().normalize().toString())
                        + "\",\"score\":" + (lastScore == null ? 0 : lastScore.total)
                        + ",\"violations\":" + lastViolationCount + "}");
    }

    private static void handleIndex(HttpExchange ex) throws IOException {
        respond(ex, 200, "text/html;charset=UTF-8", PAGE);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void respond(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ─────────────────── 可视化页面 ───────────────────
    private static final String PAGE =
            "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<title>代码质量监控平台</title><style>"
                    + "body{font-family:'Microsoft YaHei',sans-serif;margin:24px;background:#f5f7fa;color:#243043}"
                    + "h1{font-size:20px;margin:0 0 4px}.sub{color:#7a8699;font-size:13px;margin-bottom:16px}"
                    + ".cards{display:flex;gap:12px;margin-bottom:16px;flex-wrap:wrap}"
                    + ".card{background:#fff;border-radius:8px;padding:12px 18px;"
                    + "box-shadow:0 1px 4px rgba(0,0,0,.08);min-width:118px}"
                    + ".card b{display:block;font-size:21px;margin-top:5px;color:#1a6cff}"
                    + ".card span{font-size:12px;color:#7a8699}"
                    + ".card.score b{color:#12a150;font-size:26px}"
                    + "h2{font-size:15px;margin:20px 0 8px;border-left:4px solid #1a6cff;padding-left:8px}"
                    + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;"
                    + "overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.08)}"
                    + "th,td{padding:8px 11px;font-size:12.5px;text-align:left;border-bottom:1px solid #eef1f6}"
                    + "th{background:#f0f3f9;font-weight:600}tr:hover td{background:#fafcff}"
                    + ".warn{color:#e5484d;font-weight:600}.ok{color:#30a46c}.mid{color:#d97706}"
                    + "a.btn{display:inline-block;margin:10px 0;padding:7px 14px;background:#1a6cff;"
                    + "color:#fff;border-radius:6px;text-decoration:none;font-size:13px}"
                    + "</style></head><body>"
                    + "<h1>代码质量监控平台</h1>"
                    + "<div class=\"sub\">数据来源：Java Agent 字节码增强探针（运行期）+ 静态代码分析"
                    + " ｜ 持久化：H2 嵌入式数据库</div>"
                    + "<div class=\"cards\" id=\"cards\"></div>"
                    + "<a class=\"btn\" href=\"/api/report\" target=\"_blank\">导出质量报告</a>"
                    + "<h2>违规明细</h2>"
                    + "<table><thead><tr><th>规则</th><th>位置</th><th>说明</th><th>等级</th></tr>"
                    + "</thead><tbody id=\"vio\"></tbody></table>"
                    + "<h2>静态代码指标（按圈复杂度降序）</h2>"
                    + "<table><thead><tr><th>类</th><th>方法</th><th>有效行数</th><th>圈复杂度</th>"
                    + "<th>异常处理</th><th>资源管理</th></tr></thead><tbody id=\"st\"></tbody></table>"
                    + "<h2>运行期指标</h2>"
                    + "<table><thead><tr><th>类</th><th>方法</th><th>调用次数</th>"
                    + "<th>平均耗时(ms)</th><th>最大耗时(ms)</th><th>异常数</th>"
                    + "<th>异常率(%)</th><th>最近上报</th></tr></thead><tbody id=\"tb\"></tbody></table>"
                    + "<script>"
                    + "async function load(){"
                    + " const o=await (await fetch('/api/overview')).json();"
                    + " document.getElementById('cards').innerHTML="
                    + "  `<div class=card><span>应用</span><b>${o.app}</b></div>`+"
                    + "  `<div class=card score><span>质量得分</span><b>${o.score.toFixed(1)}</b>"
                    + "<span>${o.grade}</span></div>`+"
                    + "  `<div class=card><span>违规(高/中)</span><b>${o.high}/${o.mid}</b></div>`+"
                    + "  `<div class=card><span>静态方法数</span><b>${o.staticMethods}</b></div>`+"
                    + "  `<div class=card><span>平均圈复杂度</span><b>${o.avgComplexity}</b></div>`+"
                    + "  `<div class=card><span>方法覆盖率</span><b>${o.coverage}%</b>"
                    + "<span>${o.covered}/${o.instrumented}</span></div>`+"
                    + "  `<div class=card><span>运行期方法数</span><b>${o.methods}</b></div>`+"
                    + "  `<div class=card><span>上报次数</span><b>${o.reports}</b></div>`;"
                    + " const v=await (await fetch('/api/violations')).json();"
                    + " document.getElementById('vio').innerHTML = v.length? v.map(x=>"
                    + "  `<tr><td>${x.ruleName}</td><td>${x.location}</td><td>${x.detail}</td>`+"
                    + "  `<td class=\"${x.level==='高'?'warn':(x.level==='中'?'mid':'ok')}\">${x.level}</td>"
                    + "</tr>`).join('') : '<tr><td colspan=4>暂无违规</td></tr>';"
                    + " const s=await (await fetch('/api/static')).json();"
                    + " document.getElementById('st').innerHTML = s.slice(0,40).map(x=>"
                    + "  `<tr><td>${x.className}</td><td>${x.methodName}</td><td>${x.loc}</td>`+"
                    + "  `<td>${x.complexity}</td>`+"
                    + "  `<td class=\"${x.emptyCatch?'warn':'ok'}\">${x.emptyCatch?'空 catch':'正常'}</td>`+"
                    + "  `<td class=\"${x.unclosedResource?'warn':'ok'}\">"
                    + "${x.unclosedResource?'未关闭':'正常'}</td></tr>`).join('');"
                    + " const t=await (await fetch('/api/table')).json();"
                    + " document.getElementById('tb').innerHTML = t.length? t.map(r=>"
                    + "  `<tr><td>${r.className}</td><td>${r.methodName}</td><td>${r.calls}</td>`+"
                    + "  `<td>${r.avgMillis.toFixed(4)}</td><td>${r.maxMillis.toFixed(4)}</td>`+"
                    + "  `<td>${r.errors}</td>`+"
                    + "  `<td class=\"${r.errRate>0?'warn':'ok'}\">${r.errRate.toFixed(2)}</td>`+"
                    + "  `<td>${r.lastSeen}</td></tr>`).join('')"
                    + "  : '<tr><td colspan=8>等待探针上报…</td></tr>';"
                    + "}"
                    + "load();setInterval(load,4000);"
                    + "</script></body></html>";
}
