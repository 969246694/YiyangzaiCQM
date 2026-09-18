package com.cqm.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 静态代码分析器：解析 Java 源文件，产出方法级静态指标。
 *
 * <p>对应论文 5.x 静态代码质量分析模块。分析项包括：
 * <ul>
 *   <li><b>代码规模</b>：每个方法的有效代码行数（LOC，剔除空行、纯注释行与单独的大括号行）；</li>
 *   <li><b>圈复杂度</b>：McCabe 度量，1 + 判定点个数，判定点包含
 *       {@code if}、{@code else if}、{@code for}、{@code while}、{@code case}、{@code catch}
 *       以及 {@code &&}、{@code ||}、{@code ?:}；</li>
 *   <li><b>异常处理规范性</b>：识别“捕获后未做任何处理”的空 catch 块；</li>
 *   <li><b>资源管理</b>：识别打开了流/连接等资源，但既未使用 try-with-resources
 *       也未在 finally 中关闭的方法；</li>
 *   <li><b>重复代码</b>：对归一化后的代码行做长度为 N 的滑动窗口哈希，跨文件检测重复片段。</li>
 * </ul>
 *
 * <p>本实现是一个轻量级分析器，不构建完整语法树，因此对嵌套类、
 * 匿名内部类中的方法会归入外层类名统计，这是刻意保留的简化（见论文“不足与展望”）。
 */
public final class StaticAnalyzer {

    /** 判定点关键字与运算符，用于计算圈复杂度。 */
    private static final Pattern DECISION = Pattern.compile(
            "\\bif\\b|\\belse\\s+if\\b|\\bfor\\b|\\bwhile\\b|\\bcase\\b|\\bcatch\\b|&&|\\|\\||\\?[^:]*:");

    /** 方法签名：修饰符/返回类型 + 名称 + 参数列表 + { */
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:@\\w+\\s+)*"
                    + "(?:(?:public|protected|private|static|final|synchronized|abstract|native|default)\\s+)*"
                    + "(?:<[^>]+>\\s*)?"
                    + "([\\w<>\\[\\].,\\s?]+?)\\s+"
                    + "(\\w+)\\s*\\(([^;]*)\\)\\s*(?:throws\\s[\\w\\s,.]+)?\\{?\\s*$");

    /** 需要显式释放的资源创建方式。 */
    private static final Pattern RESOURCE_OPEN = Pattern.compile(
            "new\\s+(?:File(?:Input|Output)Stream|FileReader|FileWriter|BufferedReader|BufferedWriter"
                    + "|InputStreamReader|OutputStreamWriter|RandomAccessFile|Scanner)\\s*\\("
                    + "|\\.(?:getConnection|openConnection|openStream|getInputStream|getOutputStream)\\s*\\(");

    private static final Pattern HAS_CLOSE = Pattern.compile("\\.close\\s*\\(|try\\s*\\(");

    private static final int DUP_WINDOW = 10;      // 连续重复行数阈值
    private static final int MAX_FILE_BYTES = 512 * 1024;

    private StaticAnalyzer() {
    }

    /** 一个文件的处理结果：方法列表 + 归一化代码行（供重复检测）。 */
    private static final class FileResult {
        final List<Models.StaticMethod> methods = new ArrayList<>();
        final List<String> normalizedLines = new ArrayList<>();
        final List<Integer> lineNumbers = new ArrayList<>();
        String className = "(unknown)";
    }

    /**
     * 分析一个源码目录。
     *
     * @param root 源码根目录
     * @return 全部方法级静态指标
     */
    public static List<Models.StaticMethod> analyze(Path root) {
        List<Models.StaticMethod> all = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            System.err.println("[static] 源码目录不存在: " + root);
            return all;
        }
        List<FileResult> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            for (Path f : files) {
                try {
                    if (Files.size(f) > MAX_FILE_BYTES) {
                        continue;
                    }
                    FileResult r = analyzeFile(f);
                    results.add(r);
                    all.addAll(r.methods);
                } catch (IOException e) {
                    System.err.println("[static] 读取失败 " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[static] 遍历失败: " + e.getMessage());
        }

        // 重复代码检测（跨文件统计，结果以“违规方法”形式记录在 detail 中）
        Map<String, List<String>> dupIndex = new HashMap<>();
        for (FileResult r : results) {
            int n = r.normalizedLines.size();
            for (int i = 0; i + DUP_WINDOW <= n; i++) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < DUP_WINDOW; k++) {
                    sb.append(r.normalizedLines.get(i + k)).append('\n');
                }
                dupIndex.computeIfAbsent(sb.toString(), k -> new ArrayList<>())
                        .add(r.className + ":" + r.lineNumbers.get(i));
            }
        }
        DUP_BLOCKS.clear();
        for (Map.Entry<String, List<String>> e : dupIndex.entrySet()) {
            if (e.getValue().size() > 1) {
                DUP_BLOCKS.put(e.getValue().get(0), e.getValue());
            }
        }
        System.out.println("[static] 分析完成: 文件 " + results.size() + " 个, 方法 " + all.size()
                + " 个, 重复片段 " + DUP_BLOCKS.size() + " 处");
        return all;
    }

    /** 重复代码块：起始位置 -> 所有出现位置。 */
    public static final Map<String, List<String>> DUP_BLOCKS = new java.util.LinkedHashMap<>();

    private static FileResult analyzeFile(Path file) throws IOException {
        FileResult r = new FileResult();
        List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);

        // 1) 去注释（保留行结构）
        List<String> code = stripComments(raw);

        // 2) 类名
        for (String line : code) {
            Matcher m = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)").matcher(line);
            if (m.find()) {
                r.className = m.group(1);
                break;
            }
        }

        // 3) 归一化行（供重复检测）
        for (int i = 0; i < code.size(); i++) {
            String s = code.get(i).replaceAll("\\s+", " ").trim();
            if (s.isEmpty() || s.equals("{") || s.equals("}")) {
                continue;
            }
            r.normalizedLines.add(s);
            r.lineNumbers.add(i + 1);
        }

        // 4) 逐个方法分析
        int i = 0;
        while (i < code.size()) {
            String line = code.get(i);
            Matcher m = METHOD.matcher(line);
            if (m.find() && !line.contains(" class ") && !line.contains(" interface ")
                    && !line.contains(" new ") && !line.trim().startsWith("//")) {
                String name = m.group(2);
                if (!name.equals(r.className)) {
                    int braceLine = i;
                    int start = i;
                    // 若本行没有 {，向下找起始行
                    while (braceLine < code.size() && !code.get(braceLine).contains("{")) {
                        braceLine++;
                    }
                    int[] end = findMethodEnd(code, braceLine);
                    if (end != null) {
                        List<String> body = code.subList(start, end[0] + 1);
                        int complexity = calcComplexity(body);
                        int loc = calcLoc(body);
                        boolean emptyCatch = hasEmptyCatch(body);
                        boolean unclosed = hasUnclosedResource(body);
                        r.methods.add(new Models.StaticMethod(r.className, name, loc, complexity,
                                emptyCatch, unclosed, start + 1));
                        i = end[0] + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return r;
    }

    /**
     * 去掉块注释与行注释，并清空字符串/字符字面量的内容，保持行数不变。
     *
     * <p>清空字面量内容是必要的：若字面量中出现<b>不成对</b>的大括号
     * （例如 {@code String open = "{";}），按字符计数的方法边界识别会失衡，
     * 把后面所有方法都吞进当前方法，造成大面积漏报。
     * 这一点已通过对抗日志用例验证。
     *
     * <p>同时处理 Java 15 引入的文本块（{@code """}），其内容同样可能含大括号。
     */
    private static List<String> stripComments(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        boolean inTextBlock = false;
        for (String line : raw) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                if (inTextBlock) {
                    int e = line.indexOf("\"\"\"", i);
                    if (e < 0) {
                        i = line.length();
                    } else {
                        inTextBlock = false;
                        sb.append("\"\"\"");
                        i = e + 3;
                    }
                    continue;
                }
                if (inBlock) {
                    int e = line.indexOf("*/", i);
                    if (e < 0) {
                        i = line.length();
                    } else {
                        inBlock = false;
                        i = e + 2;
                    }
                    continue;
                }
                if (i + 1 < line.length() && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                    inBlock = true;
                    i += 2;
                    continue;
                }
                if (i + 1 < line.length() && line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                    break;
                }
                if (i + 2 < line.length() && line.startsWith("\"\"\"", i)) {
                    inTextBlock = true;
                    sb.append("\"\"\"");
                    i += 3;
                    continue;
                }
                char c = line.charAt(i);
                if (c == '"' || c == '\'') {
                    // 保留引号以维持词法边界，但清空内容——
                    // 字面量中的大括号与关键字不参与结构判定
                    char quote = c;
                    sb.append(quote);
                    i++;
                    while (i < line.length()) {
                        char d = line.charAt(i);
                        if (d == '\\' && i + 1 < line.length()) {
                            i += 2;
                            continue;
                        }
                        i++;
                        if (d == quote) {
                            break;
                        }
                    }
                    sb.append(quote);
                    continue;
                }
                sb.append(c);
                i++;
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 从方法体起始行（含左大括号）找到配对的右大括号所在行。 */
    private static int[] findMethodEnd(List<String> code, int startLine) {
        int depth = 0;
        boolean started = false;
        for (int i = startLine; i < code.size(); i++) {
            for (char c : code.get(i).toCharArray()) {
                if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                    if (started && depth == 0) {
                        return new int[]{i};
                    }
                }
            }
        }
        return null;
    }

    private static int calcComplexity(List<String> body) {
        int c = 1;
        for (String line : body) {
            Matcher m = DECISION.matcher(line);
            while (m.find()) {
                c++;
            }
        }
        return c;
    }

    private static int calcLoc(List<String> body) {
        int n = 0;
        for (String line : body) {
            String s = line.trim();
            if (s.isEmpty() || s.equals("{") || s.equals("}")) {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * 是否存在“捕获后未处理”的 catch 块。
     *
     * <p>实现要点：catch 行通常形如 {@code } catch (Exception e) {}，
     * 行首的 {@code }} 是 try 块的结束符，不能计入 catch 体的深度统计；
     * 因此必须从 catch 自身的左大括号开始计数。
     */
    private static boolean hasEmptyCatch(List<String> body) {
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            int ci = line.indexOf("catch");
            if (ci < 0) {
                continue;
            }
            // 找到 catch 自身的左大括号（可能在后续行）
            int startLine = i;
            int braceCol = line.indexOf('{', ci);
            while (braceCol < 0 && startLine + 1 < body.size()) {
                startLine++;
                braceCol = body.get(startLine).indexOf('{');
            }
            if (braceCol < 0) {
                continue;
            }
            StringBuilder inner = new StringBuilder();
            int depth = 0;
            boolean closed = false;
            for (int j = startLine; j < body.size() && !closed; j++) {
                String l = body.get(j);
                int from = (j == startLine) ? braceCol : 0;
                for (int k = from; k < l.length(); k++) {
                    char c = l.charAt(k);
                    if (c == '{') {
                        depth++;
                        if (depth == 1) {
                            continue;       // 跳过 catch 自身的左括号
                        }
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            closed = true;
                            break;
                        }
                    }
                    if (depth >= 1) {
                        inner.append(c);
                    }
                }
                if (!closed) {
                    inner.append('\n');
                }
            }
            String t = inner.toString().replaceAll("[\\s;]", "");
            if (t.isEmpty()) {
                return true;
            }
            i = startLine;
        }
        return false;
    }

    /** 是否打开了资源但未关闭（且未使用 try-with-resources）。 */
    private static boolean hasUnclosedResource(List<String> body) {
        String all = String.join("\n", body);
        if (!RESOURCE_OPEN.matcher(all).find()) {
            return false;
        }
        return !HAS_CLOSE.matcher(all).find();
    }
}
