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
 * 质量门禁、历史趋势、告警与报告导出能力，同时对外暴露可视化页面。
 *
 * <p>对应论文第 4、5 章的服务端模块。技术选型说明：为便于单机演示，
 * HTTP 层使用 JDK 内置的 HttpServer，持久化使用 H2 嵌入式数据库
 * （表结构与论文表 4-3 一致）；工程化部署时可替换为 Spring Boot + MySQL。
 *
 * <p><b>多应用支持</b>：平台可同时接入多个被监控应用。每个应用拥有独立的
 * 指标仓库、静态分析结果与质量得分（见 {@link AppCtx}），
 * 各应用通过探针参数 {@code -Dcqm.app=<名称>} 区分；
 * 查询接口用 {@code ?app=<名称>} 指定目标应用，缺省时返回最近上报的应用。
 * 每个应用可单独指定静态分析源码目录：{@code -Dcqm.src.<应用名>=<目录>}。
 *
 * <p>接口一览：
 * <pre>
 *   POST /api/metrics     探针上报运行期指标          需令牌
 *   GET  /api/apps        已接入应用列表
 *   GET  /api/overview    汇总数据（页面使用）
 *   GET  /api/table       运行期指标明细
 *   GET  /api/static      静态指标明细
 *   GET  /api/violations  违规记录
 *   GET  /api/score       质量评分明细
 *   GET  /api/history     质量得分历史（趋势）
 *   GET  /api/gate        质量门禁判定结果
 *   GET  /api/alarms      告警记录
 *   GET  /api/report      导出 HTML 质量报告
 *   GET  /api/analyze     触发静态分析（?path=）     需令牌或本机
 *   GET  /api/stats       全局计数
 *   GET  /                可视化页面
 * </pre>
 */
public final class Server {

    /**
     * 运行期指标条目的解析模式。
     *
     * <p>各字段之间允许出现任意空白：探针自身输出的是紧凑 JSON，但第三方接入方
     * 可能使用标准格式化输出（键值之间有空格）。若只匹配紧凑形式，
     * 带空格的报文会被静默忽略（表现为 {@code accepted:0} 且指标丢失），
     * 这类故障很难排查。因此这里对空白保持宽容。
     */
    private static final Pattern METRIC = Pattern.compile(
            "\\{\\s*\"className\"\\s*:\\s*\"([^\"]*)\"\\s*,"
                    + "\\s*\"methodName\"\\s*:\\s*\"([^\"]*)\"\\s*,"
                    + "\\s*\"calls\"\\s*:\\s*(\\d+)\\s*,"
                    + "\\s*\"totalNanos\"\\s*:\\s*(\\d+)\\s*,"
                    + "\\s*\"errors\"\\s*:\\s*(\\d+)\\s*,"
                    + "\\s*\"maxNanos\"\\s*:\\s*(\\d+)\\s*,"
                    + "\\s*\"minNanos\"\\s*:\\s*(\\d+)\\s*,"
                    + "\\s*\"avgMillis\"\\s*:\\s*([0-9.eE+-]+)\\s*}");

    /** 应用名：同样容忍键值之间的空白。 */
    private static final Pattern APP_NAME = Pattern.compile("\"app\"\\s*:\\s*\"([^\"]*)\"");

    /** 已插桩方法数。 */
    private static final Pattern INSTRUMENTED = Pattern.compile("\"instrumented\"\\s*:\\s*(\\d+)");

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

    /**
     * 单个被监控应用的上下文。
     *
     * <p>平台支持同时接入多个应用：每个应用独立维护运行期指标、插桩方法数、
     * 静态分析结果、违规记录与质量得分，互不干扰。
     */
    static final class AppCtx {
        final String name;
        final int projectId;
        final Map<String, Agg> store = new ConcurrentHashMap<>();
        volatile int instrumented;
        volatile Models.Score score;
        volatile int violationCount;
        volatile int staticMethods;
        volatile String sourcePath;
        volatile int filesParsed;
        volatile int filesFailed;
        volatile int duplicates;
        volatile long lastReportAt;
        volatile long lastEvalAt;

        AppCtx(String name, int projectId, String sourcePath) {
            this.name = name;
            this.projectId = projectId;
            this.sourcePath = sourcePath;
        }
    }

    private static final Map<String, AppCtx> APPS = new ConcurrentHashMap<>();
    private static volatile String primaryApp = null;

    private static final LongAdder REPORT_COUNT = new LongAdder();
    private static final LongAdder REPORT_BYTES = new LongAdder();
    private static volatile long lastReportAt = 0L;
    private static volatile String lastReportPath = "-";

    // ─────────────────── 安全控制 ───────────────────
    /** 写操作访问令牌；为空表示不校验（仅建议本地开发）。 */
    private static final String TOKEN = System.getProperty("cqm.token", "");

    /** 允许被静态分析的源码根目录（绝对路径），防止路径穿越。 */
    private static volatile String srcRoot =
            Paths.get(System.getProperty("cqm.src", "demo/src"))
                    .toAbsolutePath().normalize().toString();

    private static boolean authorized(HttpExchange ex) {
        if (TOKEN.isEmpty()) {
            return true;
        }
        return TOKEN.equals(ex.getRequestHeaders().getFirst("X-CQM-Token"));
    }

    private static boolean fromLoopback(HttpExchange ex) {
        InetSocketAddress addr = ex.getRemoteAddress();
        return addr != null && addr.getAddress() != null && addr.getAddress().isLoopbackAddress();
    }

    private static boolean insideSrcRoot(String candidate) {
        try {
            Path p = Paths.get(candidate).toAbsolutePath().normalize();
            Path root = Paths.get(srcRoot).toAbsolutePath().normalize();
            return p.startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────── 启动 ───────────────────
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Db.init();
        // 预接入：由 -Dcqm.app 指定默认应用名，便于单应用场景零配置启动
        ctxOf(System.getProperty("cqm.app", "demo-app"));
        primaryApp = System.getProperty("cqm.app", "demo-app");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/metrics", Server::handleMetrics);
        server.createContext("/api/apps", Server::handleApps);
        server.createContext("/api/report", Server::handleReport);
        server.createContext("/api/analyze", Server::handleAnalyze);
        server.createContext("/api/overview", Server::handleOverview);
        server.createContext("/api/table", Server::handleTable);
        server.createContext("/api/static", Server::handleStatic);
        server.createContext("/api/violations", Server::handleViolations);
        server.createContext("/api/score", Server::handleScore);
        server.createContext("/api/history", Server::handleHistory);
        server.createContext("/api/gate", Server::handleGate);
        server.createContext("/api/alarms", Server::handleAlarms);
        server.createContext("/api/stats", Server::handleStats);
        server.createContext("/", Server::handleIndex);
        server.setExecutor(Executors.newFixedThreadPool(6));
        server.start();
        System.out.println("cqm-server started: http://127.0.0.1:" + port + "/");
        System.out.println("  源码目录(静态分析): "
                + Paths.get(System.getProperty("cqm.src", "demo/src")).toAbsolutePath().normalize());
        System.out.println("  复杂度口径: " + StaticAnalyzer.PROFILE.id);
        System.out.println("  安全策略: 写操作令牌=" + (TOKEN.isEmpty() ? "未启用（仅限本地开发）" : "已启用")
                + "，静态分析路径限制=" + srcRoot);

        if (Boolean.parseBoolean(System.getProperty("cqm.autoscan", "true"))) {
            for (AppCtx c : APPS.values()) {
                try {
                    analyzeAndEvaluate(c, true);
                } catch (Throwable t) {
                    System.err.println("[analyze] 启动扫描失败: " + t);
                }
            }
        }
    }

    /** 取得应用上下文；首次见到该应用时自动登记项目。 */
    private static AppCtx ctxOf(String app) {
        AppCtx ctx = APPS.get(app);
        if (ctx != null) {
            return ctx;
        }
        synchronized (Server.class) {
            ctx = APPS.get(app);
            if (ctx == null) {
                int pid = Db.ensureProject(app, System.getProperty("cqm.pkg." + app, ""));
                String src = System.getProperty("cqm.src." + app,
                        System.getProperty("cqm.src", "demo/src"));
                ctx = new AppCtx(app, pid, src);
                APPS.put(app, ctx);
                System.out.println("[app] 新接入应用: " + app + " (projectId=" + pid
                        + ", 源码目录=" + src + ")");
            }
        }
        return ctx;
    }

    /** 解析请求目标应用：优先 ?app=，其次最近上报的应用，最后任一已接入应用。 */
    private static AppCtx resolveApp(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                if (kv.startsWith("app=")) {
                    AppCtx c = APPS.get(java.net.URLDecoder.decode(kv.substring(4),
                            StandardCharsets.UTF_8));
                    if (c != null) {
                        return c;
                    }
                }
            }
        }
        String p = primaryApp;
        AppCtx c = p == null ? null : APPS.get(p);
        return c != null ? c : APPS.values().stream().findFirst().orElse(null);
    }

    // ─────────────────── 静态分析 + 规则 + 评分 ───────────────────
    /** 评估节流间隔（毫秒），可通过 -Dcqm.evalInterval 调整。 */
    private static final long EVAL_INTERVAL = Long.getLong("cqm.evalInterval", 3000L);

    /**
     * 执行一次"静态分析 → 规则检测 → 评分 → 落库"。
     *
     * <p>节流按<b>应用</b>独立计算（记录在各应用上下文的 {@code lastEvalAt}），
     * 而非全局：否则一个应用的上报会压制其他应用的评估。
     * 另外，应用首次评估（尚未产生过得分）不受节流限制，
     * 以保证新接入的应用立刻有静态分析与评分结果。
     *
     * @param force true 表示强制评估（手动触发接口使用）
     */
    private static void analyzeAndEvaluate(AppCtx ctx, boolean force) {
        long now = System.currentTimeMillis();
        boolean firstTime = ctx.score == null;
        if (!force && !firstTime && now - ctx.lastEvalAt < EVAL_INTERVAL) {
            return;
        }
        synchronized (ctx) {
            ctx.lastEvalAt = System.currentTimeMillis();
        }
        StaticAnalyzer.Result analysis =
                StaticAnalyzer.analyze(Paths.get(ctx.sourcePath).toAbsolutePath().normalize());
        List<Models.StaticMethod> statics = analysis.methods;
        Db.saveStaticMetrics(ctx.projectId, statics);
        ctx.staticMethods = statics.size();
        ctx.filesParsed = analysis.filesParsed;
        ctx.filesFailed = analysis.filesFailed;
        ctx.duplicates = analysis.duplicates.size();

        Models.Coverage cov = new Models.Coverage();
        cov.instrumented = ctx.instrumented;
        cov.covered = ctx.store.size();
        cov.ratio = cov.instrumented == 0 ? 0 : cov.covered * 100d / cov.instrumented;

        List<Models.Metric> metrics = modelsOf(ctx.store.values());
        List<Models.Violation> violations =
                RuleEngine.check(statics, metrics, analysis.duplicates);
        Db.saveViolations(ctx.projectId, violations);
        ctx.violationCount = violations.size();

        Models.Score score = ScoreCalculator.score(statics, metrics, violations, cov);
        ctx.score = score;
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Db.saveScore(ctx.projectId, score.total, today);
        Db.saveCoverage(ctx.projectId, cov.instrumented, cov.covered, cov.ratio, today);

        List<Models.Alarm> alarms = new ArrayList<>();
        for (Models.Metric m : metrics) {
            if (m.errRate > 5) {
                alarms.add(new Models.Alarm("异常率", m.errRate, 5,
                        m.className + "#" + m.methodName + " 异常率 "
                                + String.format("%.2f%%", m.errRate) + "，超过阈值 5%"));
            }
        }
        Db.saveAlarms(ctx.projectId, alarms);

        System.out.printf("[analyze] %s: 静态方法 %d 个，运行期方法 %d 个，违规 %d 条，"
                        + "覆盖率 %.1f%%（%d/%d），得分 %.1f（%s）%n",
                ctx.name, statics.size(), metrics.size(), violations.size(), cov.ratio,
                cov.covered, cov.instrumented, score.total, score.grade);
    }

    // ─────────────────── 接收上报 ───────────────────
    private static void handleMetrics(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "method not allowed");
            return;
        }
        if (!authorized(ex)) {
            System.err.println("[security] 拒绝未授权上报，来源 " + ex.getRemoteAddress());
            respond(ex, 401, "application/json;charset=UTF-8", "{\"error\":\"unauthorized\"}");
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        REPORT_COUNT.increment();
        REPORT_BYTES.add(body.length());
        lastReportAt = System.currentTimeMillis();

        String app = "demo-app";
        Matcher appM = APP_NAME.matcher(body);
        if (appM.find() && !appM.group(1).isBlank()) {
            app = appM.group(1);
        }
        AppCtx ctx = ctxOf(app);
        primaryApp = app;
        ctx.lastReportAt = lastReportAt;

        Matcher inst = INSTRUMENTED.matcher(body);
        if (inst.find()) {
            ctx.instrumented = Integer.parseInt(inst.group(1));
        }

        Matcher m = METRIC.matcher(body);
        int n = 0;
        while (m.find()) {
            String key = m.group(1) + "#" + m.group(2);
            Agg agg = ctx.store.computeIfAbsent(key, k -> new Agg(m.group(1), m.group(2)));
            agg.merge(Long.parseLong(m.group(3)), Long.parseLong(m.group(4)),
                    Long.parseLong(m.group(5)), Long.parseLong(m.group(6)),
                    Long.parseLong(m.group(7)));
            n++;
        }
        Db.saveMetrics(ctx.projectId, modelsOf(ctx.store.values()));

        try {
            analyzeAndEvaluate(ctx, false);
        } catch (Throwable t) {
            System.err.println("[analyze] 评估失败: " + t);
        }
        respond(ex, 200, "application/json", "{\"accepted\":" + n + ",\"app\":\"" + app + "\"}");
    }

    private static List<Models.Metric> modelsOf(java.util.Collection<Agg> aggs) {
        List<Models.Metric> out = new ArrayList<>(aggs.size());
        for (Agg a : aggs) {
            out.add(a.toModel());
        }
        return out;
    }

    // ─────────────────── 查询接口 ───────────────────
    private static void handleApps(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (AppCtx c : APPS.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(esc(c.name))
                    .append("\",\"methods\":").append(c.store.size())
                    .append(",\"instrumented\":").append(c.instrumented)
                    .append(",\"staticMethods\":").append(c.staticMethods)
                    .append(",\"score\":").append(c.score == null ? 0 : c.score.total)
                    .append(",\"lastReportAt\":").append(c.lastReportAt)
                    .append('}');
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleTable(HttpExchange ex) throws IOException {
        AppCtx ctx = resolveApp(ex);
        if (ctx == null) {
            respond(ex, 200, "application/json;charset=UTF-8", "[]");
            return;
        }
        List<Models.Metric> list = modelsOf(ctx.store.values());
        list.sort(Comparator.comparingDouble((Models.Metric a) -> a.avgMillis * a.calls).reversed());
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Models.Metric a = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            Agg raw = ctx.store.get(a.className + "#" + a.methodName);
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
        AppCtx ctx = resolveApp(ex);
        List<Models.StaticMethod> list = ctx == null ? new ArrayList<>()
                : Db.staticMetrics(ctx.projectId);
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
                    .append(",\"file\":\"").append(esc(m.file))
                    .append("\",\"startLine\":").append(m.startLine)
                    .append('}');
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    private static void handleViolations(HttpExchange ex) throws IOException {
        AppCtx ctx = resolveApp(ex);
        List<Models.Violation> list = ctx == null ? new ArrayList<>() : Db.violations(ctx.projectId);
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
        AppCtx ctx = resolveApp(ex);
        Models.Score s = ctx == null ? null : ctx.score;
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

    /** 质量得分历史：用于趋势展示。 */
    private static void handleHistory(HttpExchange ex) throws IOException {
        AppCtx ctx = resolveApp(ex);
        List<double[]> hist = ctx == null ? new ArrayList<>()
                : Db.scoreHistory(ctx.projectId, 60);
        // 倒序取出，正序返回
        StringBuilder sb = new StringBuilder("[");
        for (int i = hist.size() - 1; i >= 0; i--) {
            if (i < hist.size() - 1) {
                sb.append(',');
            }
            sb.append(String.format("%.1f", hist.get(i)[0]));
        }
        sb.append(']');
        respond(ex, 200, "application/json;charset=UTF-8", sb.toString());
    }

    /**
     * 质量门禁：按可配置阈值判定项目是否通过。
     *
     * <p>阈值通过系统属性配置，例如
     * {@code -Dcqm.gate.score=70 -Dcqm.gate.high=5 -Dcqm.gate.coverage=60}。
     * 未配置时使用默认值。返回逐项判定与总体结论，可直接接入 CI。
     */
    private static void handleGate(HttpExchange ex) throws IOException {
        AppCtx ctx = resolveApp(ex);
        if (ctx == null) {
            respond(ex, 200, "application/json;charset=UTF-8",
                    "{\"passed\":false,\"reason\":\"尚无已接入应用\"}");
            return;
        }
        double minScore = Double.parseDouble(System.getProperty("cqm.gate.score", "70"));
        double maxHigh = Double.parseDouble(System.getProperty("cqm.gate.high", "5"));
        double minCoverage = Double.parseDouble(System.getProperty("cqm.gate.coverage", "50"));

        int high = RuleEngine.countByLevel(Db.violations(ctx.projectId)).getOrDefault("高", 0);
        double score = ctx.score == null ? 0 : ctx.score.total;
        int covered = ctx.store.size();
        double coverage = ctx.instrumented == 0 ? 0 : covered * 100d / ctx.instrumented;

        boolean okScore = score >= minScore;
        boolean okHigh = high <= maxHigh;
        boolean okCov = coverage >= minCoverage;
        boolean passed = okScore && okHigh && okCov;

        String json = "{\"passed\":" + passed
                + ",\"app\":\"" + esc(ctx.name) + "\""
                + ",\"items\":["
                + item("质量得分", score, minScore, okScore, ">=")
                + "," + item("高危违规数", high, maxHigh, okHigh, "<=")
                + "," + item("方法覆盖率", Math.round(coverage * 10) / 10.0, minCoverage, okCov, ">=")
                + "]}";
        respond(ex, 200, "application/json;charset=UTF-8", json);
    }

    private static String item(String name, double actual, double threshold, boolean ok, String op) {
        return "{\"name\":\"" + esc(name) + "\",\"actual\":" + actual
                + ",\"threshold\":" + threshold + ",\"op\":\"" + op + "\",\"ok\":" + ok + "}";
    }

    private static void handleAlarms(HttpExchange ex) throws IOException {
        AppCtx ctx = resolveApp(ex);
        List<Models.Alarm> list = ctx == null ? new ArrayList<>() : Db.alarms(ctx.projectId, 20);
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
        AppCtx ctx = resolveApp(ex);
        if (ctx == null) {
            respond(ex, 200, "application/json;charset=UTF-8",
                    "{\"app\":\"-\",\"methods\":0,\"reports\":0,\"staticMethods\":0,"
                            + "\"avgComplexity\":0,\"score\":0,\"grade\":\"-\","
                            + "\"instrumented\":0,\"covered\":0,\"coverage\":0,"
                            + "\"violations\":0,\"high\":0,\"mid\":0,\"apps\":0}");
            return;
        }
        Models.Score s = ctx.score;
        int[] covRow = Db.latestCoverage(ctx.projectId);
        List<Models.StaticMethod> statics = Db.staticMetrics(ctx.projectId);
        double avgCx = statics.isEmpty() ? 0
                : statics.stream().mapToInt(m -> m.complexity).average().orElse(0);
        Map<String, Integer> byLevel = RuleEngine.countByLevel(Db.violations(ctx.projectId));

        String json = "{\"app\":\"" + esc(ctx.name) + "\""
                + ",\"methods\":" + ctx.store.size()
                + ",\"reports\":" + REPORT_COUNT.sum()
                + ",\"reportBytes\":" + REPORT_BYTES.sum()
                + ",\"lastReportAt\":" + ctx.lastReportAt
                + ",\"staticMethods\":" + statics.size()
                + ",\"avgComplexity\":" + String.format("%.2f", avgCx)
                + ",\"score\":" + (s == null ? 0 : s.total)
                + ",\"grade\":\"" + (s == null ? "-" : esc(s.grade)) + "\""
                + ",\"instrumented\":" + covRow[0]
                + ",\"covered\":" + covRow[1]
                + ",\"coverage\":" + String.format("%.1f",
                        covRow[0] == 0 ? 0 : covRow[1] * 100d / covRow[0])
                + ",\"violations\":" + ctx.violationCount
                + ",\"high\":" + byLevel.getOrDefault("高", 0)
                + ",\"mid\":" + byLevel.getOrDefault("中", 0)
                + ",\"filesParsed\":" + ctx.filesParsed
                + ",\"filesFailed\":" + ctx.filesFailed
                + ",\"duplicates\":" + ctx.duplicates
                + ",\"complexityProfile\":\"" + StaticAnalyzer.PROFILE.id + "\""
                + ",\"apps\":" + APPS.size()
                + ",\"reportPath\":\"" + esc(lastReportPath) + "\"}";
        respond(ex, 200, "application/json;charset=UTF-8", json);
    }

    private static void handleStats(HttpExchange ex) throws IOException {
        int methods = APPS.values().stream().mapToInt(c -> c.store.size()).sum();
        String json = "{\"apps\":" + APPS.size()
                + ",\"primaryApp\":\"" + esc(primaryApp == null ? "-" : primaryApp) + "\""
                + ",\"methods\":" + methods
                + ",\"reports\":" + REPORT_COUNT.sum()
                + ",\"reportBytes\":" + REPORT_BYTES.sum()
                + ",\"lastReportAt\":" + lastReportAt + "}";
        respond(ex, 200, "application/json;charset=UTF-8", json);
    }

    private static void handleReport(HttpExchange ex) throws IOException {
        AppCtx ctx = resolveApp(ex);
        if (ctx == null) {
            respond(ex, 404, "application/json;charset=UTF-8", "{\"error\":\"no app\"}");
            return;
        }
        List<Models.StaticMethod> statics = Db.staticMetrics(ctx.projectId);
        List<Models.Metric> metrics = modelsOf(ctx.store.values());
        List<Models.Violation> violations = Db.violations(ctx.projectId);
        int[] covRow = Db.latestCoverage(ctx.projectId);
        Models.Coverage coverage = new Models.Coverage();
        coverage.instrumented = covRow[0];
        coverage.covered = covRow[1];
        coverage.ratio = covRow[0] == 0 ? 0 : covRow[1] * 100d / covRow[0];
        Models.Score score = ctx.score != null ? ctx.score
                : ScoreCalculator.score(statics, metrics, violations, coverage);
        Path out = ReportExporter.export(ctx.name, statics, metrics, violations, score, coverage,
                Paths.get("reports"));
        lastReportPath = out.toAbsolutePath().toString();
        respond(ex, 200, "application/json;charset=UTF-8",
                "{\"ok\":true,\"path\":\"" + esc(lastReportPath) + "\"}");
    }

    private static void handleAnalyze(HttpExchange ex) throws IOException {
        if (!authorized(ex) && !fromLoopback(ex)) {
            System.err.println("[security] 拒绝未授权分析请求，来源 " + ex.getRemoteAddress());
            respond(ex, 401, "application/json;charset=UTF-8", "{\"error\":\"unauthorized\"}");
            return;
        }
        AppCtx ctx = resolveApp(ex);
        if (ctx == null) {
            respond(ex, 404, "application/json;charset=UTF-8", "{\"error\":\"no app\"}");
            return;
        }
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                if (kv.startsWith("path=")) {
                    String requested = java.net.URLDecoder.decode(kv.substring(5),
                            StandardCharsets.UTF_8);
                    if (!insideSrcRoot(requested)) {
                        System.err.println("[security] 拒绝越界路径: " + requested
                                + "（允许范围: " + srcRoot + "）");
                        respond(ex, 403, "application/json;charset=UTF-8",
                                "{\"error\":\"path out of allowed root\",\"allowed\":\""
                                        + esc(srcRoot) + "\"}");
                        return;
                    }
                    ctx.sourcePath = requested;
                }
            }
        }
        analyzeAndEvaluate(ctx, true);
        respond(ex, 200, "application/json;charset=UTF-8",
                "{\"ok\":true,\"app\":\"" + esc(ctx.name) + "\",\"source\":\""
                        + esc(Paths.get(ctx.sourcePath).toAbsolutePath().normalize().toString())
                        + "\",\"score\":" + (ctx.score == null ? 0 : ctx.score.total)
                        + ",\"violations\":" + ctx.violationCount + "}");
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
                    + ".gate{display:inline-block;padding:6px 14px;border-radius:6px;font-size:13px;"
                    + "font-weight:600;margin-right:10px}"
                    + ".gate.pass{background:#e7f8ee;color:#12a150;border:1px solid #b7e8ca}"
                    + ".gate.fail{background:#fdecec;color:#e5484d;border:1px solid #f7c4c4}"
                    + ".spark{background:#fff;border-radius:8px;padding:12px 16px;"
                    + "box-shadow:0 1px 4px rgba(0,0,0,.08)}"
                    + "select{padding:5px 8px;border-radius:6px;border:1px solid #dfe4ec;font-size:13px}"
                    + "</style></head><body>"
                    + "<h1>代码质量监控平台</h1>"
                    + "<div class=\"sub\">数据来源：Java Agent 字节码增强探针（运行期）+ JavaParser 静态分析"
                    + " ｜ 持久化：H2 嵌入式数据库 ｜ <span id=\"prof\"></span></div>"
                    + "<div style=\"margin-bottom:12px\">被监控应用：<select id=\"appSel\"></select>"
                    + "　<span id=\"gateBox\"></span></div>"
                    + "<div class=\"cards\" id=\"cards\"></div>"
                    + "<a class=\"btn\" href=\"#\" id=\"reportLink\" target=\"_blank\">导出质量报告</a>"
                    + "<h2>质量得分趋势（最近 60 次评估）</h2>"
                    + "<div class=\"spark\"><svg id=\"trend\" width=\"100%\" height=\"90\"></svg></div>"
                    + "<h2>违规明细</h2>"
                    + "<table><thead><tr><th>规则</th><th>位置</th><th>说明</th><th>等级</th></tr>"
                    + "</thead><tbody id=\"vio\"></tbody></table>"
                    + "<h2>静态代码指标（按圈复杂度降序）</h2>"
                    + "<table><thead><tr><th>类</th><th>方法</th><th>文件:行</th><th>有效行数</th>"
                    + "<th>圈复杂度</th><th>异常处理</th><th>资源管理</th></tr></thead>"
                    + "<tbody id=\"st\"></tbody></table>"
                    + "<h2>运行期指标</h2>"
                    + "<table><thead><tr><th>类</th><th>方法</th><th>调用次数</th>"
                    + "<th>平均耗时(ms)</th><th>最大耗时(ms)</th><th>异常数</th>"
                    + "<th>异常率(%)</th><th>最近上报</th></tr></thead><tbody id=\"tb\"></tbody></table>"
                    + "<script>"
                    + "var cur=null;"
                    + "function api(p){return '/api'+p+(cur?((p.indexOf('?')<0?'?':'&')+'app='+encodeURIComponent(cur)):'');}"
                    + "function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;');}"
                    + "function trend(data){"
                    + " var svg=document.getElementById('trend');"
                    + " if(!data.length){svg.innerHTML='<text x=\"10\" y=\"45\" fill=\"#9aa5b5\" font-size=\"12\">暂无历史数据</text>';return;}"
                    + " var w=svg.clientWidth||700,h=90,pad=8;"
                    + " var mn=Math.min.apply(null,data),mx=Math.max.apply(null,data);"
                    + " if(mx-mn<1){mn=Math.max(0,mn-5);mx=Math.min(100,mx+5);}"
                    + " var pts=data.map(function(v,i){"
                    + "  var x=pad+(w-2*pad)*(data.length<2?0:i/(data.length-1));"
                    + "  var y=h-pad-(h-2*pad)*((v-mn)/(mx-mn||1));return x.toFixed(1)+','+y.toFixed(1);}).join(' ');"
                    + " var last=data[data.length-1];"
                    + " svg.innerHTML='<polyline fill=\"none\" stroke=\"#1a6cff\" stroke-width=\"2\" points=\"'+pts+'\"/>'+"
                    + "  '<text x=\"'+pad+'\" y=\"12\" fill=\"#7a8699\" font-size=\"11\">最高 '+mx.toFixed(1)+'</text>'+"
                    + "  '<text x=\"'+pad+'\" y=\"'+(h-2)+'\" fill=\"#7a8699\" font-size=\"11\">最低 '+mn.toFixed(1)+'</text>'+"
                    + "  '<text x=\"'+(w-90)+'\" y=\"14\" fill=\"#12a150\" font-size=\"13\" font-weight=\"600\">当前 '+last.toFixed(1)+'</text>';"
                    + "}"
                    + "async function loadApps(){"
                    + " var list=await (await fetch('/api/apps')).json();"
                    + " var sel=document.getElementById('appSel');"
                    + " if(!cur&&list.length)cur=list[0].name;"
                    + " sel.innerHTML=list.map(function(a){return '<option value=\"'+esc(a.name)+'\"'+(a.name===cur?' selected':'')+'>'+esc(a.name)+'</option>';}).join('');"
                    + " sel.onchange=function(){cur=sel.value;load();};"
                    + "}"
                    + "async function load(){"
                    + " await loadApps();"
                    + " var o=await (await fetch(api('/overview'))).json();"
                    + " document.getElementById('prof').textContent='静态分析：JavaParser · 复杂度口径 '+o.complexityProfile;"
                    + " document.getElementById('reportLink').href=api('/report');"
                    + " document.getElementById('cards').innerHTML="
                    + "  `<div class=card><span>被监控应用</span><b>${esc(o.app)}</b><span>共 ${o.apps} 个</span></div>`+"
                    + "  `<div class=card score><span>质量得分</span><b>${o.score.toFixed(1)}</b><span>${esc(o.grade)}</span></div>`+"
                    + "  `<div class=card><span>违规(高/中)</span><b>${o.high}/${o.mid}</b></div>`+"
                    + "  `<div class=card><span>静态方法数</span><b>${o.staticMethods}</b><span>文件 ${o.filesParsed}</span></div>`+"
                    + "  `<div class=card><span>平均圈复杂度</span><b>${o.avgComplexity}</b></div>`+"
                    + "  `<div class=card><span>方法覆盖率</span><b>${o.coverage}%</b><span>${o.covered}/${o.instrumented}</span></div>`+"
                    + "  `<div class=card><span>运行期方法数</span><b>${o.methods}</b></div>`+"
                    + "  `<div class=card><span>重复代码块</span><b>${o.duplicates}</b></div>`;"
                    + " var g=await (await fetch(api('/gate'))).json();"
                    + " document.getElementById('gateBox').innerHTML="
                    + "  '<span class=\"gate '+(g.passed?'pass':'fail')+'\">质量门禁：'+(g.passed?'通过':'未通过')+'</span>'+"
                    + "  (g.items||[]).map(function(i){return '<span style=\"font-size:12px;color:#7a8699;margin-right:10px\">'+"
                    + "   esc(i.name)+' '+i.actual+' '+(i.ok?'✓':'✗')+'（要求 '+i.op+' '+i.threshold+'）</span>';}).join('');"
                    + " trend(await (await fetch(api('/history'))).json());"
                    + " var v=await (await fetch(api('/violations'))).json();"
                    + " document.getElementById('vio').innerHTML = v.length? v.slice(0,30).map(x=>"
                    + "  `<tr><td>${esc(x.ruleName)}</td><td>${esc(x.location)}</td><td>${esc(x.detail)}</td>`+"
                    + "  `<td class=\"${x.level==='高'?'warn':(x.level==='中'?'mid':'ok')}\">${esc(x.level)}</td></tr>`).join('')"
                    + "  : '<tr><td colspan=4>暂无违规</td></tr>';"
                    + " var s=await (await fetch(api('/static'))).json();"
                    + " document.getElementById('st').innerHTML = s.slice(0,40).map(x=>"
                    + "  `<tr><td>${esc(x.className)}</td><td>${esc(x.methodName)}</td>`+"
                    + "  `<td>${esc(x.file)}:${x.startLine}</td><td>${x.loc}</td><td>${x.complexity}</td>`+"
                    + "  `<td class=\"${x.emptyCatch?'warn':'ok'}\">${x.emptyCatch?'空 catch':'正常'}</td>`+"
                    + "  `<td class=\"${x.unclosedResource?'warn':'ok'}\">${x.unclosedResource?'未关闭':'正常'}</td></tr>`).join('');"
                    + " var t=await (await fetch(api('/table'))).json();"
                    + " document.getElementById('tb').innerHTML = t.length? t.map(r=>"
                    + "  `<tr><td>${esc(r.className)}</td><td>${esc(r.methodName)}</td><td>${r.calls}</td>`+"
                    + "  `<td>${r.avgMillis.toFixed(4)}</td><td>${r.maxMillis.toFixed(4)}</td>`+"
                    + "  `<td>${r.errors}</td>`+"
                    + "  `<td class=\"${r.errRate>0?'warn':'ok'}\">${r.errRate.toFixed(2)}</td>`+"
                    + "  `<td>${esc(r.lastSeen)}</td></tr>`).join('')"
                    + "  : '<tr><td colspan=8>等待探针上报…</td></tr>';"
                    + "}"
                    + "load();setInterval(load,4000);"
                    + "</script></body></html>";
}
