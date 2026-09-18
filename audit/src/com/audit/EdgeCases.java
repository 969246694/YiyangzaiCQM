package com.audit;

/** 对抗性测试样本：考察分析器在常见 Java 写法下是否会误判。 */
public class EdgeCases {

    /** 1) 字符串字面量里含大括号——花括号计数法容易在这里出错。 */
    public String bracesInString() {
        String json = "{\"a\":1}";
        String tpl = "{0} and {1}";
        if (json.isEmpty()) {
            return tpl;
        }
        return json + tpl;
    }

    /** 2) 字符字面量里含大括号。 */
    public char braceChar() {
        char c = '{';
        char d = '}';
        return c == d ? c : d;
    }

    /** 3) 注释里含大括号与关键字。 */
    public void commentsWithBraces() {
        // 这里有个 { 和 if 和 catch
        /* 块注释 { } if while for */
        int x = 1;
    }

    /** 4) 匿名内部类——方法边界识别容易出错。 */
    public Runnable anonymousClass() {
        return new Runnable() {
            @Override
            public void run() {
                int y = 2;
            }
        };
    }

    /** 5) lambda 表达式。 */
    public java.util.function.Supplier<Integer> lambda() {
        return () -> 42;
    }

    /** 6) 正常的空 catch——应当被检出。 */
    public void realEmptyCatch() {
        try {
            Integer.parseInt("x");
        } catch (NumberFormatException e) {
        }
    }

    /** 7) 数组初始化的大括号。 */
    public int[] arrayInit() {
        int[] a = new int[] {1, 2, 3};
        int[] b = {4, 5, 6};
        return a.length > b.length ? a : b;
    }

    /** 8) 紧跟其后定义的方法——用于检验上一个方法是否吞掉了它。 */
    public int afterEdgeCases() {
        return 1;
    }
}
