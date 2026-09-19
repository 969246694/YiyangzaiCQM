package com.cqm.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量规则引擎：按论文表 4-2 定义的规则对静态指标与运行期指标做检测。
 *
 * <p>规则清单（与论文表 4-2 一致）：
 * <table border="1">
 *   <tr><th>规则名称</th><th>判定对象</th><th>判定条件</th><th>严重等级</th></tr>
 *   <tr><td>方法复杂度过高</td><td>方法</td><td>圈复杂度 &gt; 15</td><td>高</td></tr>
 *   <tr><td>方法体过长</td><td>方法</td><td>有效代码行数 &gt; 80</td><td>中</td></tr>
 *   <tr><td>重复代码块</td><td>代码块</td><td>连续重复行数 &gt; 10</td><td>中</td></tr>
 *   <tr><td>异常被忽略</td><td>方法</td><td>捕获异常后无任何处理</td><td>高</td></tr>
 *   <tr><td>资源未关闭</td><td>方法</td><td>打开流或连接后异常路径未释放</td><td>高</td></tr>
 *   <tr><td>热点方法耗时偏高</td><td>方法</td><td>平均耗时 &gt; 100ms 且调用次数 &gt; 1000</td><td>中</td></tr>
 * </table>
 *
 * <p>阈值可通过系统属性覆盖，便于按项目特点调整：
 * {@code -Dcqm.rule.complexity=15} 等。
 */
public final class RuleEngine {

    /** 规则定义。 */
    public static final class Rule {
        public final String name;
        public final String target;
        public final String condition;
        public final double threshold;
        public final String level;

        Rule(String name, String target, String condition, double threshold, String level) {
            this.name = name;
            this.target = target;
            this.condition = condition;
            this.threshold = threshold;
            this.level = level;
        }
    }

    private static double cfg(String key, double def) {
        try {
            return Double.parseDouble(System.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static final Rule RULE_COMPLEXITY =
            new Rule("方法复杂度过高", "方法", "圈复杂度 > " + (int) cfg("cqm.rule.complexity", 15),
                    cfg("cqm.rule.complexity", 15), "高");
    public static final Rule RULE_LONG_METHOD =
            new Rule("方法体过长", "方法", "有效代码行数 > " + (int) cfg("cqm.rule.loc", 80),
                    cfg("cqm.rule.loc", 80), "中");
    public static final Rule RULE_DUPLICATE =
            new Rule("重复代码块", "代码块", "连续重复行数 > " + (int) cfg("cqm.rule.dup", 10),
                    cfg("cqm.rule.dup", 10), "中");
    public static final Rule RULE_EMPTY_CATCH =
            new Rule("异常被忽略", "方法", "捕获异常后无任何处理", 0, "高");
    public static final Rule RULE_UNCLOSED_RESOURCE =
            new Rule("资源未关闭", "方法", "打开流或连接后异常路径未释放", 0, "高");
    public static final Rule RULE_HOT_SLOW =
            new Rule("热点方法耗时偏高", "方法",
                    "平均耗时 > " + (int) cfg("cqm.rule.avgMillis", 100) + "ms 且调用次数 > "
                            + (int) cfg("cqm.rule.calls", 1000),
                    cfg("cqm.rule.avgMillis", 100), "中");

    public static final List<Rule> ALL = new ArrayList<>();

    static {
        ALL.add(RULE_COMPLEXITY);
        ALL.add(RULE_LONG_METHOD);
        ALL.add(RULE_DUPLICATE);
        ALL.add(RULE_EMPTY_CATCH);
        ALL.add(RULE_UNCLOSED_RESOURCE);
        ALL.add(RULE_HOT_SLOW);
    }

    private RuleEngine() {
    }

    /**
     * 执行全部规则检测。
     *
     * @param statics  静态分析结果
     * @param metrics  运行期指标
     * @param dupBlocks 重复代码块（起始位置 -&gt; 出现位置列表）
     * @return 违规记录列表
     */
    public static List<Models.Violation> check(List<Models.StaticMethod> statics,
                                               List<Models.Metric> metrics,
                                               Map<String, List<String>> dupBlocks) {
        List<Models.Violation> out = new ArrayList<>();

        // ── 静态规则 ──
        for (Models.StaticMethod m : statics) {
            String loc = m.className + "#" + m.methodName;
            if (m.complexity > RULE_COMPLEXITY.threshold) {
                out.add(new Models.Violation(RULE_COMPLEXITY.name, m.className, loc,
                        "圈复杂度 " + m.complexity + "，超过阈值 " + (int) RULE_COMPLEXITY.threshold,
                        RULE_COMPLEXITY.level));
            }
            if (m.loc > RULE_LONG_METHOD.threshold) {
                out.add(new Models.Violation(RULE_LONG_METHOD.name, m.className, loc,
                        "有效代码行数 " + m.loc + "，超过阈值 " + (int) RULE_LONG_METHOD.threshold,
                        RULE_LONG_METHOD.level));
            }
            if (m.emptyCatch) {
                out.add(new Models.Violation(RULE_EMPTY_CATCH.name, m.className, loc,
                        "存在捕获异常后未做任何处理的 catch 块，异常信息被静默吞掉",
                        RULE_EMPTY_CATCH.level));
            }
            if (m.unclosedResource) {
                out.add(new Models.Violation(RULE_UNCLOSED_RESOURCE.name, m.className, loc,
                        "打开了流/连接等资源，但未使用 try-with-resources 也未在 finally 中关闭",
                        RULE_UNCLOSED_RESOURCE.level));
            }
        }

        // ── 重复代码规则 ──
        if (dupBlocks != null) {
            for (Map.Entry<String, List<String>> e : dupBlocks.entrySet()) {
                if (e.getValue().size() > 1) {
                    out.add(new Models.Violation(RULE_DUPLICATE.name, "代码块",
                            e.getValue().get(0),
                            "存在连续 " + (int) RULE_DUPLICATE.threshold + " 行以上的重复代码，共 "
                                    + e.getValue().size() + " 处："
                                    + String.join("、", e.getValue()),
                            RULE_DUPLICATE.level));
                }
            }
        }

        // ── 动态规则 ──
        for (Models.Metric m : metrics) {
            if (m.calls > cfg("cqm.rule.calls", 1000)
                    && m.avgMillis > RULE_HOT_SLOW.threshold) {
                out.add(new Models.Violation(RULE_HOT_SLOW.name, m.className,
                        m.className + "#" + m.methodName,
                        String.format("热点方法：调用 %d 次，平均耗时 %.2fms，超过阈值 %dms",
                                m.calls, m.avgMillis, (int) RULE_HOT_SLOW.threshold),
                        RULE_HOT_SLOW.level));
            }
        }
        return out;
    }

    /** 按严重等级统计违规数量，用于评分与展示。 */
    public static Map<String, Integer> countByLevel(List<Models.Violation> violations) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("高", 0);
        map.put("中", 0);
        map.put("低", 0);
        for (Models.Violation v : violations) {
            map.merge(v.level == null ? "低" : v.level, 1, Integer::sum);
        }
        return map;
    }
}
