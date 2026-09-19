package com.demo;

/**
 * 性能基准：固定时长内尽可能多地执行业务方法，输出调用次数与耗时，
 * 用于对比"关闭探针 / 开启探针"两种情况下的吞吐量与平均耗时。
 *
 * <p>对应论文 6.3 性能测试与开销评估。测量方法：
 * <ul>
 *   <li>固定运行时长（默认 15 秒），统计完成的业务调用总次数与墙钟耗时；</li>
 *   <li>吞吐量 = 总调用次数 / 墙钟耗时；平均单次耗时 = 墙钟耗时 / 总调用次数；</li>
 *   <li>同一进程内取稳态阶段数据，避免 JIT 预热影响；先预热 3 秒不计入统计。</li>
 * </ul>
 */
public class Bench {

    private static final int[] DATA = buildData();

    private static int[] buildData() {
        int[] d = new int[200];
        java.util.Random r = new java.util.Random(11L);
        for (int i = 0; i < d.length; i++) {
            d[i] = r.nextInt(300) - 150;
        }
        return d;
    }

    public static void main(String[] args) throws Exception {
        // 参数：mode(typical|micro) [seconds] [warmupSeconds]
        String mode = args.length > 0 ? args[0] : "typical";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        int warmupSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        if ("micro".equalsIgnoreCase(mode)) {
            runMicro(seconds, warmupSeconds);
        } else {
            runTypical(seconds, warmupSeconds);
        }
    }

    private static void runTypical(int seconds, int warmupSeconds) throws Exception {
        BizService svc = new BizService();
        long warmEnd = System.currentTimeMillis() + warmupSeconds * 1000L;
        long sink = 0;
        while (System.currentTimeMillis() < warmEnd) {
            sink += runOnce(svc);
        }

        long ops = 0;
        long t0 = System.nanoTime();
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < 50; i++) {
                sink += runOnce(svc);
                ops++;
            }
        }
        report("typical", ops, System.nanoTime() - t0, sink);
    }

    /** 一次性业务操作中捕获并计入的预期异常次数。 */
    private static final java.util.concurrent.atomic.LongAdder IGNORED =
            new java.util.concurrent.atomic.LongAdder();

    /** 一次典型业务操作：高频短方法 + 高复杂度方法 + 条件异常 + 偶发慢调用。 */
    private static long runOnce(BizService svc) throws InterruptedException {
        long s = 0;
        for (int i = 0; i < 10; i++) {
            s += svc.simpleQuery(i).length();
        }
        s += svc.complexAggregate(DATA);
        try {
            svc.riskyOperation((int) (s % 100));
        } catch (IllegalStateException e) {
            // 基准测试中该异常属于预期路径，计入次数而不静默吞掉
            IGNORED.increment();
        }
        return s;
    }

    /**
     * 纯计算微基准：只调用无 IO 的短方法，用于测量探针的<b>单次调用固定开销</b>。
     * 该场景会放大插桩开销占比，用于给出开销的上界；真实业务负载见 runTypical。
     */
    private static void runMicro(int seconds, int warmupSeconds) throws Exception {
        BizService svc = new BizService();

        long warmEnd = System.currentTimeMillis() + warmupSeconds * 1000L;
        long sink = 0;
        while (System.currentTimeMillis() < warmEnd) {
            sink += microLoop(svc);
        }
        long ops = 0;
        long t0 = System.nanoTime();
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < 100; i++) {
                sink += microLoop(svc);
                ops += 11;
            }
        }
        report("micro", ops, System.nanoTime() - t0, sink);
    }

    /** 纯 CPU 循环：10 次 quickAdd + 1 次 hashLoop，无休眠。 */
    private static long microLoop(BizService svc) {
        long s = 0;
        for (int i = 0; i < 10; i++) {
            s += svc.quickAdd(i);
        }
        s += svc.hashLoop(500);
        return s;
    }

    private static void report(String tag, long ops, long elapsedNs, long sink) throws Exception {
        double elapsedSec = elapsedNs / 1e9;
        Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(200);
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.out.println("BENCH_RESULT"
                + " tag=" + tag
                + " ops=" + ops
                + " elapsedSec=" + String.format("%.3f", elapsedSec)
                + " throughput=" + String.format("%.1f", ops / elapsedSec)
                + " avgMicros=" + String.format("%.4f", elapsedNs / 1000d / ops)
                + " heapUsedMb=" + usedMb
                + " sink=" + (sink & 0xFFFF));
    }
}
