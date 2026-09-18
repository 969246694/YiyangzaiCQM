package com.cqm.server;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台数据模型集中定义。
 *
 * <p>与论文第 4 章数据表设计（表 4-3）对应：
 * {@link Metric} 对应运行期指标，{@link StaticMethod} 对应静态分析结果，
 * {@link Violation} 对应违规记录，{@link Alarm} 对应告警记录，{@link Score} 对应质量评分。
 */
public final class Models {

    private Models() {
    }

    /** 运行期指标（探针上报并聚合后的方法级数据）。 */
    public static final class Metric {
        public String className;
        public String methodName;
        public long calls;
        public double avgMillis;
        public double maxMillis;
        public double minMillis;
        public long errors;
        public double errRate;

        public Metric() {
        }

        public Metric(String className, String methodName, long calls, double avgMillis,
                      double maxMillis, double minMillis, long errors, double errRate) {
            this.className = className;
            this.methodName = methodName;
            this.calls = calls;
            this.avgMillis = avgMillis;
            this.maxMillis = maxMillis;
            this.minMillis = minMillis;
            this.errors = errors;
            this.errRate = errRate;
        }
    }

    /** 静态分析得到的单个方法信息。 */
    public static final class StaticMethod {
        public String className;
        public String methodName;
        public int loc;                 // 有效代码行数
        public int complexity;          // 圈复杂度
        public boolean emptyCatch;      // 是否捕获异常后未处理
        public boolean unclosedResource;// 是否打开资源后未在异常路径释放
        public int startLine;

        public StaticMethod(String className, String methodName, int loc, int complexity,
                            boolean emptyCatch, boolean unclosedResource, int startLine) {
            this.className = className;
            this.methodName = methodName;
            this.loc = loc;
            this.complexity = complexity;
            this.emptyCatch = emptyCatch;
            this.unclosedResource = unclosedResource;
            this.startLine = startLine;
        }
    }

    /** 一条违规记录。 */
    public static final class Violation {
        public String ruleName;
        public String target;     // 类或方法
        public String location;   // 具体位置（类#方法）
        public String detail;
        public String level;      // 高 / 中 / 低

        public Violation(String ruleName, String target, String location, String detail, String level) {
            this.ruleName = ruleName;
            this.target = target;
            this.location = location;
            this.detail = detail;
            this.level = level;
        }
    }

    /** 一条告警记录。 */
    public static final class Alarm {
        public String metric;
        public double value;
        public double threshold;
        public String detail;
        public long createdAt;

        public Alarm(String metric, double value, double threshold, String detail) {
            this.metric = metric;
            this.value = value;
            this.threshold = threshold;
            this.detail = detail;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /** 质量评分结果，含各指标归一化值，便于前端展示评分构成。 */
    public static final class Score {
        public double total;                       // 0~100
        public final List<String> items = new ArrayList<>();   // "指标名: 归一化值 x 权重"
        public String grade;                       // 优 / 良 / 中 / 及格 / 不及格

        public void add(String name, double normalized, double weight) {
            items.add(String.format("%s: 归一化 %.3f × 权重 %.2f", name, normalized, weight));
        }
    }

    /** 覆盖率统计。 */
    public static final class Coverage {
        public int instrumented;   // 已插桩方法数
        public int covered;        // 实际被调用过的方法数
        public double ratio;       // 覆盖率（%）
    }
}
