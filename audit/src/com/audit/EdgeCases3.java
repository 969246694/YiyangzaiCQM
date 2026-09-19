package com.audit;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 第三轮对抗性用例：覆盖 AST 方案应当正确处理、而正则方案容易出错的语法特性。
 * 每个方法的期望值都在测试脚本中逐项断言。
 */
public class EdgeCases3 {

    /** 1) switch 分支计数：3 个 case + default，default 不计。 */
    public String switchCase(int x) {
        switch (x) {
            case 1:
                return "一";
            case 2:
                return "二";
            case 3:
                return "三";
            default:
                return "其他";
        }
    }

    /** 2) 逻辑运算符：2 个 && 与 1 个 ||，各计一次。 */
    public boolean logicalOps(int a, int b, int c) {
        boolean r = a > 0 && b > 0;
        if (r || c > 0) {
            return true;
        }
        return a > 0 && b > 0 && c > 0;
    }

    /** 3) 循环：for + foreach + while + do-while，共 4 个判定点。 */
    public int loops(int[] arr) {
        int sum = 0;
        for (int i = 0; i < arr.length; i++) {
            sum += arr[i];
        }
        for (int v : arr) {
            sum += v;
        }
        int k = 0;
        while (k < 3) {
            k++;
        }
        do {
            k--;
        } while (k > 0);
        return sum + k;
    }

    /** 4) 文本块中含大括号——正则计数法会失衡，AST 不受影响。 */
    public String textBlock() {
        String json = """
                {
                  "a": 1,
                  "b": { "c": 2 }
                }
                """;
        return json.trim();
    }

    /** 5) 方法内的局部类：其方法应作为独立条目，不计入本方法复杂度。 */
    public int localClass() {
        class Helper {
            int compute(int n) {
                if (n > 0) {
                    return n * 2;
                }
                return 0;
            }
        }
        Helper h = new Helper();
        return h.compute(3);
    }

    /** 6) lambda 中的判定点属于当前方法作用域，应计入。 */
    public long lambdaComplexity(int[] arr) {
        return java.util.Arrays.stream(arr)
                .filter(v -> v > 0)
                .map(v -> v % 2 == 0 ? v : -v)
                .count();
    }

    /** 7) try-with-resources 且带 catch：不应误报资源未关闭，catch 计 1 个判定点。 */
    public int resourceWithCatch(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.read();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 8) 声明了资源但只在正常路径关闭，异常路径未关闭。 */
    public int leakOnException(String path) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(path));
        int c = r.read();
        r.close();
        return c;
    }

    /** 9) 枚举中的方法。 */
    public enum Level {
        LOW, HIGH;

        public boolean isHigh() {
            return this == HIGH;
        }
    }

    /** 10) 嵌套静态类，应独立统计，不归入外层类。 */
    public static class Inner {
        public int innerMethod(int n) {
            if (n > 0) {
                return n;
            }
            return -n;
        }
    }
}
