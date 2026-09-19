package com.audit;

import java.io.FileInputStream;
import java.io.InputStream;

/** 第二轮对抗性测试：针对真正容易失效的写法。 */
public class EdgeCases2 {

    /** 1) 字符串里含<b>不成对</b>的大括号——花括号计数会失衡，方法边界可能识别错。 */
    public String unbalancedBraceInString() {
        String open = "{";
        return open;
    }

    /** 2) 上一个方法若被误判吞掉，这个方法就会消失或复杂度异常。 */
    public int shouldBeDetected() {
        if (1 > 0) {
            return 1;
        }
        return 2;
    }

    /** 3) 使用 try-with-resources 的方法——不应被判定为"资源未关闭"。 */
    public int properResource(String path) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            return in.read();
        }
    }

    /** 4) 在 finally 中关闭资源——不应被判定为"资源未关闭"。 */
    public int closedInFinally(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        try {
            return in.read();
        } finally {
            in.close();
        }
    }

    /** 5) 捕获后仅打印日志——不应算作空 catch。 */
    public void catchWithLog() {
        try {
            Integer.parseInt("x");
        } catch (NumberFormatException e) {
            System.err.println(e.getMessage());
        }
    }

    /** 6) 嵌套类中的方法——当前实现会归到外层类，属于已知简化。 */
    public static class Nested {
        public int nestedMethod() {
            return 7;
        }
    }
}
