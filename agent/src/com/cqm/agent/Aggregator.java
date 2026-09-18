package com.cqm.agent;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 聚合器门面：把对 {@link MetricsAggregator} 的访问统一收敛到"引导类加载器中的那一份"。
 *
 * <p>为什么需要这个类：探针 JAR 在应用类路径上，而 {@code MetricsAggregator} 同时被
 * 追加进了引导类加载器。若探针代码直接用 {@code MetricsAggregator.drain()}，
 * 解析顺序不确定，可能出现"探针用一份、插桩代码用另一份"的情况——
 * 两份静态状态互相不可见，表现为服务端收不到任何数据。
 *
 * <p>此处通过指定 {@code ClassLoader.getSystemClassLoader().getParent()}
 * （即平台/引导加载器）解析类，并在 {@code premain} 阶段先行触发初始化，
 * 保证全 JVM 只有一份静态状态。
 */
public final class Aggregator {

    private static Class<?> aggregatorClass;
    private static Method drainMethod;
    private static Method startTimeMethod;
    private static Method registerMethod;
    private static Method countMethod;

    private Aggregator() {
    }

    /** 在 premain 阶段调用，强制使用引导类加载器解析并初始化聚合器。 */
    public static synchronized void init() throws Exception {
        if (aggregatorClass != null) {
            return;
        }
        ClassLoader bootstrap = ClassLoader.getSystemClassLoader().getParent();
        aggregatorClass = Class.forName("com.cqm.agent.MetricsAggregator", true, bootstrap);
        drainMethod = aggregatorClass.getMethod("drain");
        startTimeMethod = aggregatorClass.getMethod("startTime");
        registerMethod = aggregatorClass.getMethod("registerInstrumented", String.class, String.class);
        countMethod = aggregatorClass.getMethod("instrumentedCount");
        System.out.println("[cqm-agent] aggregator resolved from: "
                + aggregatorClass.getClassLoader());
    }

    /** 登记一个已插桩方法（用于计算覆盖率）。 */
    public static void registerInstrumented(String className, String methodName) throws Exception {
        init();
        registerMethod.invoke(null, className, methodName);
    }

    /** 已插桩方法总数。 */
    public static int instrumentedCount() throws Exception {
        init();
        return (Integer) countMethod.invoke(null);
    }

    /** 取出并重置当前周期的聚合快照。返回元素类型为 MetricsAggregator.Snapshot。 */
    public static List<?> drain() throws Exception {
        init();
        return (List<?>) drainMethod.invoke(null);
    }

    public static long startTime() throws Exception {
        init();
        return (Long) startTimeMethod.invoke(null);
    }

    /** 反射读取 Snapshot 字段（该类在引导加载器中，编译期不可直接引用）。 */
    public static Object field(Object snapshot, String name) throws Exception {
        return snapshot.getClass().getField(name).get(snapshot);
    }
}
