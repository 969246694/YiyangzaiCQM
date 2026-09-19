package com.cqm.agent;

import net.bytebuddy.asm.Advice;

/**
 * 方法插桩逻辑：进入时记录起始纳秒，退出时计算耗时并写入聚合器。
 *
 * <p>对应论文 5.2.2 方法插桩实现：
 * <ul>
 *   <li>{@code @Advice.OnMethodEnter} / {@code @Advice.OnMethodExit} 构成方法前后切点；</li>
 *   <li>{@code onThrowable} 使异常路径也能正确统计耗时与异常次数；</li>
 *   <li>整个采集过程包裹在 try/catch 中，采集代码自身异常绝不影响业务方法语义。</li>
 * </ul>
 */
public final class TimingAdvice {

    private TimingAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Origin("#t") String className,
                            @Advice.Origin("#m") String methodName,
                            @Advice.Enter long startNanos,
                            @Advice.Thrown Throwable thrown) {
        try {
            long cost = System.nanoTime() - startNanos;
            MetricsAggregator.record(className, methodName, cost, thrown != null);
        } catch (Throwable ignored) {
            // 采集失败绝不影响业务
        }
    }
}
