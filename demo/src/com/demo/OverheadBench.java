package com.demo;

import java.util.Random;

/**
 * 开销对比基准：在指定时长内尽可能多地执行操作，输出每次操作的耗时。
 *
 * <p><b>为什么按"时长"而不是"固定轮数"</b>：示例应用中不同方法的耗时差异极大
 * （纯 CPU 方法为纳秒级，而 {@code complexAggregate}、{@code slowExternalCall}
 * 分别含 3ms 与 20~50ms 的模拟耗时）。若固定轮数，纳秒级负载只需数毫秒，
 * 而毫秒级负载要跑几十分钟，无法用同一套参数比较。按固定时长运行可让计时窗口
 * 与负载无关，同时保证每种配置都被测量同样长的时间，结果更具可比性。
 *
 * <p>两种负载形态：
 * <ul>
 *   <li>{@code cpu}   —— 纯 CPU 微方法（quickAdd / hashLoop），对探针开销最敏感；</li>
 *   <li>{@code mixed} —— 加入毫秒级模拟 I/O 与条件异常，贴近真实业务形态。</li>
 * </ul>
 *
 * <p>用法：{@code OverheadBench <cpu|mixed> <计时秒数> <预热秒数>}
 * 输出以 {@code RESULT} 前缀单行呈现，便于脚本解析。
 */
public final class OverheadBench {

    private static final int[] DATA = new int[]{5, 3, 9, 1, 7, 2, 8, 4, 6, 0};

    private static long sink = 0;

    private OverheadBench() {
    }

    private static void cpuOnce(BizService svc, int seed) {
        for (int i = 0; i < 10; i++) {
            sink += svc.quickAdd(seed + i);
        }
        sink += svc.hashLoop(64);
    }

    /**
     * 贴近真实业务形态的负载：在 CPU 微方法之外，加入一段确定性的亚毫秒业务处理
     * 与一次条件异常调用。
     *
     * <p>刻意不使用 {@code simpleQuery}/{@code complexAggregate} 等含
     * {@code Thread.sleep} 的方法：操作系统定时器精度会使单次耗时在数毫秒间抖动，
     * 噪声远大于探针开销本身，无法得出可靠结论。
     */
    private static void mixedOnce(BizService svc, int seed) {
        cpuOnce(svc, seed);
        sink += svc.simulatedWork(200_000);
        try {
            svc.riskyOperation(seed);
        } catch (IllegalStateException e) {
            // 基准中的预期路径
        }
    }

    private static long spin(BizService svc, Random r, boolean mixed, long deadlineNanos) {
        long n = 0;
        while (System.nanoTime() < deadlineNanos) {
            for (int k = 0; k < 64; k++) {
                if (mixed) {
                    mixedOnce(svc, r.nextInt(100));
                } else {
                    cpuOnce(svc, r.nextInt(100));
                }
            }
            n += 64;
        }
        return n;
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "cpu";
        boolean mixed = "mixed".equalsIgnoreCase(mode);
        double seconds = args.length > 1 ? Double.parseDouble(args[1]) : (mixed ? 4 : 4);
        double warmupSeconds = args.length > 2 ? Double.parseDouble(args[2]) : 2;

        BizService svc = new BizService();
        Random r = new Random(20260919L);

        // 预热：让即时编译充分生效
        spin(svc, r, mixed, System.nanoTime() + (long) (warmupSeconds * 1e9));

        long t0 = System.nanoTime();
        long deadline = t0 + (long) (seconds * 1e9);
        long iters = spin(svc, r, mixed, deadline);
        long elapsed = System.nanoTime() - t0;

        double nsPerOp = (double) elapsed / iters;
        System.out.printf("RESULT mode=%s seconds=%.2f iters=%d elapsed_ms=%.3f "
                        + "ns_per_op=%.2f ops_per_sec=%.0f sink=%d%n",
                mode, seconds, iters, elapsed / 1_000_000.0, nsPerOp,
                1_000_000_000.0 / nsPerOp, sink & 0xFFFF);
    }
}
