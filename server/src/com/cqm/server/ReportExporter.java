package com.cqm.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 质量报告导出器：把静态指标、运行期指标、违规记录与质量得分汇总为一个
 * 自包含的 HTML 报告（样式内嵌，便于直接归档或打印为 PDF）。
 *
 * <p>对应论文 5.x 中的报告导出功能。
 */
public final class ReportExporter {

    private ReportExporter() {
    }

    public static Path export(String project, List<Models.StaticMethod> statics,
                              List<Models.Metric> metrics, List<Models.Violation> violations,
                              Models.Score score, Models.Coverage coverage, Path outDir)
            throws IOException {
        Files.createDirectories(outDir);
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path out = outDir.resolve("质量报告-" + project + "-" + ts + ".html");

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>代码质量报告 - ").append(esc(project)).append("</title><style>")
                .append("body{font-family:'Microsoft YaHei',sans-serif;margin:32px;color:#243043;background:#fff}")
                .append("h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:26px 0 8px;")
                .append("border-left:4px solid #1a6cff;padding-left:8px}")
                .append(".meta{color:#7a8699;font-size:13px;margin-bottom:20px}")
                .append(".score{display:inline-block;background:#f0f6ff;border:1px solid #bcd6ff;")
                .append("border-radius:8px;padding:14px 22px;margin:12px 0}")
                .append(".score b{font-size:30px;color:#1a6cff;margin-left:10px}")
                .append("table{width:100%;border-collapse:collapse;margin-top:8px;font-size:13px}")
                .append("th,td{border:1px solid #e3e8f0;padding:7px 10px;text-align:left}")
                .append("th{background:#f4f7fc}tr:nth-child(even) td{background:#fafcff}")
                .append(".high{color:#e5484d;font-weight:600}.mid{color:#d97706}")
                .append("</style></head><body>");

        sb.append("<h1>代码质量监控报告</h1>")
                .append("<div class=\"meta\">项目：").append(esc(project))
                .append(" ｜ 生成时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
                .append(" ｜ 数据来源：Java Agent 字节码增强探针 + 静态代码分析</div>");

        // 得分
        sb.append("<div class=\"score\">质量得分：<b>")
                .append(String.format("%.1f", score.total)).append("</b>（").append(esc(score.grade))
                .append("）</div>");
        sb.append("<h2>评分构成</h2><table><tr><th>指标</th></tr>");
        for (String it : score.items) {
            sb.append("<tr><td>").append(esc(it)).append("</td></tr>");
        }
        sb.append("</table>");

        // 概览
        sb.append("<h2>指标概览</h2><table>")
                .append("<tr><th>分析项</th><th>数值</th></tr>")
                .append("<tr><td>静态分析方法数</td><td>").append(statics.size()).append("</td></tr>")
                .append("<tr><td>运行期监控方法数</td><td>").append(metrics.size()).append("</td></tr>")
                .append("<tr><td>违规记录数</td><td>").append(violations.size()).append("</td></tr>");
        if (coverage != null) {
            sb.append("<tr><td>方法覆盖率</td><td>").append(coverage.covered).append(" / ")
                    .append(coverage.instrumented).append("（")
                    .append(String.format("%.1f", coverage.ratio)).append("%）</td></tr>");
        }
        sb.append("</table>");

        // 违规明细
        sb.append("<h2>违规明细（").append(violations.size()).append(" 条）</h2>")
                .append("<table><tr><th>规则</th><th>位置</th><th>说明</th><th>等级</th></tr>");
        for (Models.Violation v : violations) {
            String cls = "高".equals(v.level) ? "high" : ("中".equals(v.level) ? "mid" : "");
            sb.append("<tr><td>").append(esc(v.ruleName)).append("</td><td>").append(esc(v.location))
                    .append("</td><td>").append(esc(v.detail)).append("</td><td class=\"")
                    .append(cls).append("\">").append(esc(v.level)).append("</td></tr>");
        }
        sb.append("</table>");

        // 静态指标明细（按复杂度降序）
        sb.append("<h2>静态指标明细（按圈复杂度降序，前 50）</h2>")
                .append("<table><tr><th>类</th><th>方法</th><th>有效行数</th><th>圈复杂度</th>")
                .append("<th>异常处理</th><th>资源管理</th></tr>");
        statics.stream()
                .sorted((a, b) -> Integer.compare(b.complexity, a.complexity))
                .limit(50)
                .forEach(m -> sb.append("<tr><td>").append(esc(m.className)).append("</td><td>")
                        .append(esc(m.methodName)).append("</td><td>").append(m.loc)
                        .append("</td><td>").append(m.complexity).append("</td><td>")
                        .append(m.emptyCatch ? "<span class=\"high\">空 catch</span>" : "正常")
                        .append("</td><td>")
                        .append(m.unclosedResource ? "<span class=\"high\">未关闭</span>" : "正常")
                        .append("</td></tr>"));
        sb.append("</table>");

        // 运行期指标明细（按总耗时降序）
        sb.append("<h2>运行期指标明细（前 50）</h2>")
                .append("<table><tr><th>类</th><th>方法</th><th>调用次数</th><th>平均耗时(ms)</th>")
                .append("<th>最大耗时(ms)</th><th>异常数</th><th>异常率(%)</th></tr>");
        metrics.stream()
                .sorted((a, b) -> Double.compare(b.avgMillis * b.calls, a.avgMillis * a.calls))
                .limit(50)
                .forEach(m -> sb.append("<tr><td>").append(esc(m.className)).append("</td><td>")
                        .append(esc(m.methodName)).append("</td><td>").append(m.calls)
                        .append("</td><td>").append(String.format("%.4f", m.avgMillis))
                        .append("</td><td>").append(String.format("%.4f", m.maxMillis))
                        .append("</td><td>").append(m.errors).append("</td><td>")
                        .append(String.format("%.2f", m.errRate)).append("</td></tr>"));
        sb.append("</table>");

        sb.append("<div class=\"meta\" style=\"margin-top:24px\">")
                .append("本报告由代码质量监控平台自动生成。</div>")
                .append("</body></html>");

        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[report] 已导出: " + out);
        pruneOldReports(outDir);
        return out;
    }

    /**
     * 仅保留最新的 {@code KEEP_REPORTS} 份报告。
     *
     * <p>报告导出是面向使用者的功能，长期在线运行且被反复点击时，
     * 报告文件会不断累积占用磁盘；因此每次导出后清理较旧的报告。
     */
    private static void pruneOldReports(Path outDir) {
        int keep = Integer.getInteger("cqm.report.keep", 20);
        try (java.util.stream.Stream<Path> list = Files.list(outDir)) {
            java.util.List<Path> files = list
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
            for (int i = keep; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            System.err.println("[report] 清理旧报告失败: " + e.getMessage());
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
