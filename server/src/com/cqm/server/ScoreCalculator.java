package com.cqm.server;

import java.util.List;

/**
 * 质量评分器：基于"目标值 + 上限"双锚点的归一化，再加权求和，输出 0~100 的项目质量得分。
 *
 * <h3>为什么不用单纯的"除以参考上限"</h3>
 * 最初的实现把每一项指标除以一个参考上限（如"平均圈复杂度 / 15"）再截断。
 * 对 4 个真实开源项目（gson、commons-lang3、junit4、jsoup，共 7867 个方法）的统计表明：
 * 项目级平均圈复杂度实际落在 <b>1.60 ~ 2.60</b>，平均方法行数落在 <b>4.06 ~ 6.83</b>；
 * 而当时设定的上限是 15 与 80，比实际取值高一个数量级，
 * 导致所有项目的该项归一化值都接近 1，指标<b>完全失去区分度</b>。
 *
 * <h3>本版本采用的模型</h3>
 * 每项指标设两个锚点：
 * <ul>
 *   <li><b>目标值 target</b>：维护良好项目的典型水平，达到即得满分；</li>
 *   <li><b>上限 ceiling</b>：明显不佳的水平，达到即得 0 分。</li>
 * </ul>
 * 负向指标（越小越好）：{@code normalized = (ceiling - actual) / (ceiling - target)}；
 * 正向指标（越大越好）：{@code normalized = (actual - floor) / (target - floor)}；
 * 两者都截断到 [0, 1]。
 *
 * <h3>锚点的取值依据</h3>
 * <table border="1">
 *   <tr><th>指标</th><th>target</th><th>ceiling</th><th>依据</th></tr>
 *   <tr><td>高危违规数</td><td>0</td><td>40</td>
 *       <td>语料中项目级高危违规最多 34 条（commons-lang3），取略高于其上</td></tr>
 *   <tr><td>平均圈复杂度</td><td>2.0</td><td>8.0</td>
 *       <td>语料实测 1.60~2.60；ceiling 取实测最大值的约 3 倍</td></tr>
 *   <tr><td>平均方法行数</td><td>4.0</td><td>20.0</td>
 *       <td>语料实测 4.06~6.83；ceiling 取实测最大值的约 3 倍</td></tr>
 *   <tr><td>平均异常率</td><td>0</td><td>5.0</td>
 *       <td>以 5% 方法调用异常为明显偏高的工程经验值</td></tr>
 *   <tr><td>方法覆盖率</td><td>80%</td><td>0%</td>
 *       <td>以 80% 覆盖率为业界常用验收线</td></tr>
 * </table>
 *
 * <p>权重反映各指标相对重要性，属于管理决策而非可由数据推导的量，
 * 因此保持可配置（{@code -Dcqm.weight.*}），并通过敏感性分析验证
 * 评分与排序对权重扰动不敏感（见论文相关章节）。
 * 所有锚点与权重均可通过系统属性覆盖，便于按团队标准调整。
 */
public final class ScoreCalculator {

    // ── 锚点：由真实语料统计校准，见类注释 ──
    private static final double VIOLATION_TARGET = cfg("cqm.score.violation.target", 0);
    private static final double VIOLATION_CEILING = cfg("cqm.score.violation.ceiling", 40);
    private static final double COMPLEXITY_TARGET = cfg("cqm.score.complexity.target", 2.0);
    private static final double COMPLEXITY_CEILING = cfg("cqm.score.complexity.ceiling", 8.0);
    private static final double LOC_TARGET = cfg("cqm.score.loc.target", 4.0);
    private static final double LOC_CEILING = cfg("cqm.score.loc.ceiling", 20.0);
    private static final double ERR_TARGET = cfg("cqm.score.errRate.target", 0);
    private static final double ERR_CEILING = cfg("cqm.score.errRate.ceiling", 5.0);
    private static final double COV_TARGET = cfg("cqm.score.coverage.target", 80.0);
    private static final double COV_FLOOR = cfg("cqm.score.coverage.floor", 0);

    private static double cfg(String key, double def) {
        try {
            return Double.parseDouble(System.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double weight(String key, double def) {
        return cfg(key, def);
    }

    private ScoreCalculator() {
    }

    /** 负向指标：实际值越小越好。达到 target 得 1，达到 ceiling 得 0。 */
    static double normLowerBetter(double actual, double target, double ceiling) {
        if (ceiling <= target) {
            return 1;
        }
        return clamp01((ceiling - actual) / (ceiling - target));
    }

    /** 正向指标：实际值越大越好。达到 target 得 1，低至 floor 得 0。 */
    static double normHigherBetter(double actual, double floor, double target) {
        if (target <= floor) {
            return 1;
        }
        return clamp01((actual - floor) / (target - floor));
    }

    /**
     * 计算质量得分。
     *
     * @param statics    静态分析结果
     * @param metrics    运行期指标
     * @param violations 违规记录
     * @param coverage   覆盖率（可为 null）
     * @return 评分结果，含各项归一化明细
     */
    public static Models.Score score(List<Models.StaticMethod> statics,
                                     List<Models.Metric> metrics,
                                     List<Models.Violation> violations,
                                     Models.Coverage coverage) {
        Models.Score s = new Models.Score();

        // 1) 高危违规数（负向）
        int high = 0;
        for (Models.Violation v : violations) {
            if ("高".equals(v.level)) {
                high++;
            }
        }
        double nViolation = normLowerBetter(high, VIOLATION_TARGET, VIOLATION_CEILING);
        double wViolation = weight("cqm.weight.violation", 0.30);
        s.add(String.format("高危违规数(%d，目标≤%.0f/上限%.0f)", high,
                VIOLATION_TARGET, VIOLATION_CEILING), nViolation, wViolation);

        // 2) 平均圈复杂度（负向）
        double avgComplexity = statics.isEmpty() ? 0
                : statics.stream().mapToInt(m -> m.complexity).average().orElse(0);
        double nComplexity = statics.isEmpty() ? 1
                : normLowerBetter(avgComplexity, COMPLEXITY_TARGET, COMPLEXITY_CEILING);
        double wComplexity = weight("cqm.weight.complexity", 0.25);
        s.add(String.format("平均圈复杂度(%.2f，目标≤%.1f/上限%.1f)", avgComplexity,
                COMPLEXITY_TARGET, COMPLEXITY_CEILING), nComplexity, wComplexity);

        // 3) 平均方法行数（负向）
        double avgLoc = statics.isEmpty() ? 0
                : statics.stream().mapToInt(m -> m.loc).average().orElse(0);
        double nLoc = statics.isEmpty() ? 1
                : normLowerBetter(avgLoc, LOC_TARGET, LOC_CEILING);
        double wLoc = weight("cqm.weight.loc", 0.15);
        s.add(String.format("平均方法行数(%.1f，目标≤%.1f/上限%.1f)", avgLoc,
                LOC_TARGET, LOC_CEILING), nLoc, wLoc);

        // 4) 运行期异常率（负向）
        double avgErr = metrics.isEmpty() ? 0
                : metrics.stream().mapToDouble(m -> m.errRate).average().orElse(0);
        double nErr = normLowerBetter(avgErr, ERR_TARGET, ERR_CEILING);
        double wErr = weight("cqm.weight.errRate", 0.15);
        s.add(String.format("平均异常率(%.2f%%，目标≤%.0f%%/上限%.0f%%)", avgErr,
                ERR_TARGET, ERR_CEILING), nErr, wErr);

        // 5) 方法覆盖率（正向）
        double cov = coverage == null ? 0 : coverage.ratio;
        double nCov = normHigherBetter(cov, COV_FLOOR, COV_TARGET);
        double wCov = weight("cqm.weight.coverage", 0.15);
        s.add(String.format("方法覆盖率(%.1f%%，目标≥%.0f%%)", cov, COV_TARGET), nCov, wCov);

        double total = nViolation * wViolation + nComplexity * wComplexity
                + nLoc * wLoc + nErr * wErr + nCov * wCov;
        double wSum = wViolation + wComplexity + wLoc + wErr + wCov;
        s.total = wSum <= 0 ? 0 : Math.round(total / wSum * 1000) / 10.0;
        s.grade = grade(s.total);
        return s;
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        return v > 1 ? 1 : v;
    }

    /** 按学校规定的 90/80/70/60 分档给出评价。 */
    public static String grade(double score) {
        if (score >= 90) {
            return "优秀";
        }
        if (score >= 80) {
            return "良好";
        }
        if (score >= 70) {
            return "中等";
        }
        if (score >= 60) {
            return "及格";
        }
        return "不及格";
    }
}
