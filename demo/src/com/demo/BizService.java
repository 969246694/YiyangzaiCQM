package com.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 示例业务应用：模拟一个典型的分层业务服务，包含不同复杂度与耗时特征的方法，
 * 用于验证探针的采集能力，并作为论文第 6 章性能测试的被测对象。
 *
 * <p>方法设计意图：
 * <ul>
 *   <li>{@code simpleQuery} 短耗时、高频调用，用于观察高频场景下的插桩开销；</li>
 *   <li>{@code complexAggregate} 高圈复杂度、中等耗时，对应论文中的"方法复杂度过高"规则；</li>
 *   <li>{@code slowExternalCall} 模拟外部调用，长耗时；</li>
 *   <li>{@code riskyOperation} 在特定条件下抛异常，用于验证异常统计与异常路径耗时采集。</li>
 * </ul>
 */
public class BizService {

    private final Random random = new Random(42L);
    private final Map<String, String> cache = new HashMap<>();

    /** 短耗时、高频。用 1ms 休眠模拟一次缓存/索引查询的真实耗时。 */
    public String simpleQuery(int id) {
        sleepQuietly(1);
        String key = "k" + (id % 64);
        String v = cache.get(key);
        if (v == null) {
            v = "value-" + key;
            cache.put(key, v);
        }
        return v;
    }

    /** 高圈复杂度：分支多、嵌套深。用 3ms 休眠模拟一次中等开销的数据聚合。 */
    public int complexAggregate(int[] data) {
        sleepQuietly(3);
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            int v = data[i];
            if (v > 0) {
                if (v % 2 == 0) {
                    if (v > 100) {
                        sum += v * 2;
                    } else if (v > 50) {
                        sum += v + 10;
                    } else {
                        sum += v;
                    }
                } else {
                    if (v % 3 == 0) {
                        sum += v * 3;
                    } else if (v % 5 == 0) {
                        sum += v * 5;
                    } else {
                        sum -= v;
                    }
                }
            } else if (v < 0) {
                if (v < -100) {
                    sum -= 50;
                } else {
                    sum -= 1;
                }
            }
        }
        return sum;
    }

    /** 模拟外部调用，长耗时。 */
    public List<String> slowExternalCall(int n) throws InterruptedException {
        Thread.sleep(20 + random.nextInt(30));
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add("item-" + i);
        }
        return list;
    }

    /** 特定条件抛异常，用于验证异常路径采集。 */
    public void riskyOperation(int seed) {
        if (seed % 7 == 0) {
            throw new IllegalStateException("simulated failure, seed=" + seed);
        }
        if (seed % 3 == 0) {
            cache.put("seed" + seed, String.valueOf(seed));
        }
    }

    private static int[] makeData(Random r) {
        int[] d = new int[200];
        for (int i = 0; i < d.length; i++) {
            d[i] = r.nextInt(300) - 150;
        }
        return d;
    }

    /** 模拟真实业务耗时；被中断时恢复中断标志，保持业务语义。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 以下两个方法专供微基准使用：纯 CPU、无休眠，用于测量探针的单次固定开销 ──

    /** 纯计算短方法：一次加法与取模，执行时间为纳秒级。 */
    public int quickAdd(int v) {
        int r = v + 1;
        return r % 7 == 0 ? r - 1 : r;
    }

    /** 纯计算循环：500 次哈希扰动，执行时间为微秒级。 */
    public int hashLoop(int n) {
        int h = 17;
        for (int i = 0; i < n; i++) {
            h = h * 31 + i;
            h ^= (h >>> 13);
        }
        return h;
    }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        BizService svc = new BizService();
        System.out.println("demo-app started, will run " + seconds + "s ...");
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        Random r = new Random(7L);
        long calls = 0;
        long ignoredCount = 0;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < 200; i++) {
                svc.simpleQuery(i);
                calls++;
            }
            svc.complexAggregate(makeData(r));
            calls++;
            if (r.nextInt(10) == 0) {
                svc.slowExternalCall(5);
                calls++;
            }
            try {
                svc.riskyOperation(r.nextInt(100));
            } catch (IllegalStateException e) {
                // 业务方选择忽略该异常，但计入计数，避免静默吞掉异常
                ignoredCount++;
            }
            calls++;
            Thread.sleep(5);
        }
        System.out.println("demo-app finished, total calls=" + calls
                + ", ignored exceptions=" + ignoredCount);
    }
}
