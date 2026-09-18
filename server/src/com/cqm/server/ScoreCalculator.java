package com.cqm.server;

import java.util.List;

/**
 * 质量评分器：极值归一化 + 加权求和，输出 0~100 的项目质量得分。
 *
 * <p>对应论文 4.x 与 5.4 质量规则检测与评分模块。
 *
 * <p>指标方向不同，归一化方式也不同：
 * <ul>
 *   <li><b>负向指标</b>（越小越好）：违规数、平均复杂度、平均方法行数、异常率 →
 *       {@code normalized = 1 - min(实际值/参考上限, 1)}</li>
 *   <li><b>正向指标</b>（越大越好）：方法覆盖率 →
 *       {@code normalized = min(实际值/参考上限, 1)}</li>
 * </ul>
 * 随后按权重加权求和再乘 100，得到总分。
 *
 * <p>权重可通过系统属性覆盖，例如 {@code -Dcqm.weight.violation=0.35}。
 */
public final class ScoreCalculator {

    /** 各指标的参考上限，用于归一化。 */
    private static final double REF_HIGH_VIOLATION = 20;   // 高危违规 20 条即视为 0 分
    private static final double REF_COMPLEXITY = 15;       // 平均圈复杂度上限
    private static final double REF_LOC = 80;              // 平均方法行数上限
    private static final double REF_ERR_RATE = 10;         // 异常率 10% 即视为 0 分

    private static double weight(String key, double def) {
        try {
            return Double.parseDouble(System.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private ScoreCalculator() {
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
        double nViolation = 1 - clamp01(high / REF_HIGH_VIOLATION);
        s.add("高危违规数(" + high + ")", nViolation, weight("cqm.weight.violation", 0.35));

        // 2) 平均圈复杂度（负向）
        double avgComplexity = statics.isEmpty() ? 0
                : statics.stream().mapToInt(m -> m.complexity).average().orElse(0);
        double nComplexity = 1 - clamp01(avgComplexity / REF_COMPLEXITY);
        s.add(String.format("平均圈复杂度(%.2f)", avgComplexity), nComplexity,
                weight("cqm.weight.complexity", 0.20));

        // 3) 平均方法行数（负向）
        double avgLoc = statics.isEmpty() ? 0
                : statics.stream().mapToInt(m -> m.loc).average().orElse(0);
        double nLoc = 1 - clamp01(avgLoc / REF_LOC);
        s.add(String.format("平均方法行数(%.1f)", avgLoc), nLoc,
                weight("cqm.weight.loc", 0.15));

        // 4) 运行期异常率（负向）
        double avgErr = metrics.isEmpty() ? 0
                : metrics.stream().mapToDouble(m -> m.errRate).average().orElse(0);
        double nErr = 1 - clamp01(avgErr / REF_ERR_RATE);
        s.add(String.format("平均异常率(%.2f%%)", avgErr), nErr,
                weight("cqm.weight.errRate", 0.15));

        // 5) 方法覆盖率（正向）
        double cov = coverage == null ? 0 : coverage.ratio;
        double nCov = clamp01(cov / 100d);
        s.add(String.format("方法覆盖率(%.1f%%)", cov), nCov,
                weight("cqm.weight.coverage", 0.15));

        // 加权求和
        double total = 0;
        double wSum = 0;
        total += nViolation * weight("cqm.weight.violation", 0.35);
        total += nComplexity * weight("cqm.weight.complexity", 0.20);
        total += nLoc * weight("cqm.weight.loc", 0.15);
        total += nErr * weight("cqm.weight.errRate", 0.15);
        total += nCov * weight("cqm.weight.coverage", 0.15);
        wSum += weight("cqm.weight.violation", 0.35);
        wSum += weight("cqm.weight.complexity", 0.20);
        wSum += weight("cqm.weight.loc", 0.15);
        wSum += weight("cqm.weight.errRate", 0.15);
        wSum += weight("cqm.weight.coverage", 0.15);

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

    /** 按学校规定的等级划分给出评价。 */
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
