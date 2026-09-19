package com.cqm.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 指标聚合器：在探针端按「类#方法」维度做本地聚合。
 *
 * <p><b>该类会被放入引导类加载器</b>（见 build.py 生成的 cqm-agent-bootstrap.jar），
 * 因为插桩代码被内联进业务类后，由业务类的类加载器执行，必须能在引导加载器中解析到本类。
 * 为保证全 JVM 只有一份静态状态，本类<b>只能引用 JDK 类型</b>，
 * 且不放在探针 JAR 中；探针侧通过 {@link Aggregator} 反射访问。
 *
 * <p>设计要点（对应论文 4.2.3 抖动抑制设计与 5.2.3 聚合上报实现）：
 * <ul>
 *   <li>用 {@link ConcurrentHashMap} + 原子累加器，保证多线程插桩下无锁竞争；</li>
 *   <li>把大量单次调用压缩为一条聚合记录，显著降低上报数据量与网络交互次数；</li>
 *   <li>同时保留耗时分布直方图，用于后续计算分位数。</li>
 * </ul>
 */
public final class MetricsAggregator {

    /** 耗时直方图桶上界（毫秒），最后一桶为溢出桶。 */
    private static final long[] BUCKETS = {1, 5, 10, 50, 100, 500, 1000, Long.MAX_VALUE};

    /** 细粒度采样率：小于 1 时按比例采集，用于控制高频方法的开销。 */
    private static final double SAMPLE =
            Double.parseDouble(System.getProperty("cqm.sample", "1.0"));

    private static final AtomicLong SAMPLE_COUNTER = new AtomicLong();

    public static final class MethodMetric {
        final String className;
        final String methodName;
        final LongAdder calls = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final LongAdder errors = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        final LongAdder[] histogram = new LongAdder[BUCKETS.length];

        MethodMetric(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
            for (int i = 0; i < histogram.length; i++) {
                histogram[i] = new LongAdder();
            }
        }

        void record(long nanos, boolean error) {
            calls.increment();
            totalNanos.add(nanos);
            if (error) {
                errors.increment();
            }
            maxNanos.accumulateAndGet(nanos, Math::max);
            minNanos.accumulateAndGet(nanos, Math::min);
            long ms = nanos / 1_000_000L;
            for (int i = 0; i < BUCKETS.length; i++) {
                if (ms < BUCKETS[i]) {
                    histogram[i].increment();
                    break;
                }
            }
        }
    }

    private static final Map<String, MethodMetric> METRICS = new ConcurrentHashMap<>();
    private static final AtomicLong START_TIME = new AtomicLong(System.currentTimeMillis());

    /** 一次性诊断标记：首次采集时打印一次，便于确认插桩代码已生效。 */
    private static final AtomicBoolean FIRST_HIT_LOGGED = new AtomicBoolean();

    private MetricsAggregator() {
    }

    /**
     * 已插桩方法登记表：插桩时登记，运行时是否被调用另行统计，
     * 二者相除即得方法覆盖率。本探针采集的是<b>方法级覆盖</b>
     * （某方法是否被执行），而非字节码级分支覆盖，这是刻意保留的实现边界。
     */
    private static final java.util.Set<String> INSTRUMENTED = ConcurrentHashMap.newKeySet();

    /** 插桩时登记一个被监控方法（由探针在类转换阶段调用）。 */
    public static void registerInstrumented(String className, String methodName) {
        INSTRUMENTED.add(className + "#" + methodName);
    }

    /** 已插桩方法总数。 */
    public static int instrumentedCount() {
        return INSTRUMENTED.size();
    }

    public static MethodMetric metricOf(String className, String methodName) {
        return METRICS.computeIfAbsent(className + "#" + methodName,
                k -> new MethodMetric(className, methodName));
    }

    /** 记录一次方法调用。插桩代码只调用这一个方法，尽量减少对业务的影响。 */
    public static void record(String className, String methodName, long nanos, boolean error) {
        if (SAMPLE < 1.0d && (SAMPLE_COUNTER.incrementAndGet() & 0xFFFF) / 65536d >= SAMPLE) {
            return;
        }
        if (FIRST_HIT_LOGGED.compareAndSet(false, true)) {
            System.out.println("[cqm-agent] first metric collected: " + className + "#" + methodName
                    + " (aggregator loader=" + MetricsAggregator.class.getClassLoader() + ")");
        }
        metricOf(className, methodName).record(nanos, error);
    }

    public static long startTime() {
        return START_TIME.get();
    }

    /** 取出并重置当前周期的聚合快照。 */
    public static List<Snapshot> drain() {
        List<Snapshot> list = new ArrayList<>(METRICS.size());
        for (Map.Entry<String, MethodMetric> e : METRICS.entrySet()) {
            MethodMetric m = e.getValue();
            long calls = m.calls.sumThenReset();
            if (calls == 0) {
                continue;
            }
            long total = m.totalNanos.sumThenReset();
            long errors = m.errors.sumThenReset();
            long max = m.maxNanos.getAndSet(0L);
            long min = m.minNanos.getAndSet(Long.MAX_VALUE);
            long[] hist = new long[m.histogram.length];
            for (int i = 0; i < hist.length; i++) {
                hist[i] = m.histogram[i].sumThenReset();
            }
            list.add(new Snapshot(m.className, m.methodName, calls, total, errors, max,
                    min == Long.MAX_VALUE ? 0L : min, hist));
        }
        return list;
    }

    /** 一条聚合记录。 */
    public static final class Snapshot {
        public final String className;
        public final String methodName;
        public final long calls;
        public final long totalNanos;
        public final long errors;
        public final long maxNanos;
        public final long minNanos;
        public final long[] histogram;

        Snapshot(String className, String methodName, long calls, long totalNanos,
                 long errors, long maxNanos, long minNanos, long[] histogram) {
            this.className = className;
            this.methodName = methodName;
            this.calls = calls;
            this.totalNanos = totalNanos;
            this.errors = errors;
            this.maxNanos = maxNanos;
            this.minNanos = minNanos;
            this.histogram = histogram;
        }

        public double avgMillis() {
            return calls == 0 ? 0d : (totalNanos / 1_000_000d) / calls;
        }
    }
}
