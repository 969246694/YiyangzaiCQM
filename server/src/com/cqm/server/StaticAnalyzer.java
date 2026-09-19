package com.cqm.server;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 静态代码分析器：基于 <b>JavaParser 抽象语法树</b>解析 Java 源码，产出方法级静态指标。
 *
 * <p>相比基于正则与花括号计数的轻量实现，AST 方案具有以下优势（也是本文改用它的原因）：
 * <ul>
 *   <li><b>方法边界精确</b>：不再依赖花括号配对，字符串/字符字面量、文本块、
 *       数组初始化中的括号都不会干扰边界识别；</li>
 *   <li><b>嵌套类归属正确</b>：内部类、静态嵌套类、匿名类与局部类中的方法
 *       按其真实所属类型统计，而非统一归入外层类；</li>
 *   <li><b>复杂度计算可靠</b>：直接统计语法树中的判定节点，
 *       不受代码换行、注释与书写风格影响。</li>
 * </ul>
 *
 * <p>分析项：
 * <ul>
 *   <li><b>代码规模</b>：方法所在行区间内的有效代码行数
 *       （剔除空行、纯注释行与单独的大括号行）；</li>
 *   <li><b>圈复杂度</b>：McCabe 度量，基数为 1，判定点包括
 *       {@code if}、{@code for}、{@code foreach}、{@code while}、{@code do}、
 *       {@code catch}、{@code switch} 的 case 分支（不含 default）、
 *       三元表达式以及 {@code &&} 与 {@code ||} 运算符；
 *       嵌套类中的方法单独统计，不计入外层方法；</li>
 *   <li><b>异常处理规范性</b>：捕获后方法体为空语句的 catch 块；</li>
 *   <li><b>资源管理</b>：创建了流、连接等资源，既未使用 try-with-resources，
 *       也没有在任何位置调用 {@code close()}；</li>
 *   <li><b>重复代码</b>：对归一化后的代码行做固定长度滑动窗口哈希，
 *       跨文件检测重复片段。</li>
 * </ul>
 */
public final class StaticAnalyzer {

    /**
     * 圈复杂度计算口径。
     *
     * <p>圈复杂度在业界存在多套实现约定，差异集中在三处：布尔路径（{@code &&}、
     * {@code ||}）是否计数、{@code throw} 是否计数、lambda 体内的判定点归属。
     * 本平台把口径做成可配置，以便与不同工具对齐并做交叉验证：
     *
     * <table border="1">
     *   <tr><th>口径</th><th>布尔路径</th><th>throw</th><th>lambda 体</th></tr>
     *   <tr><td>{@link #MCCABE}（默认）</td><td>任意位置均计数</td><td>不计数</td>
     *       <td>计入外层方法</td></tr>
     *   <tr><td>{@link #PMD}</td><td>仅在控制语句的条件位置计数</td><td>计数</td>
     *       <td>忽略</td></tr>
     * </table>
     *
     * <p>MCCABE 口径与 Checkstyle 的 CyclomaticComplexity 一致，
     * 且不会遗漏 lambda 内部的复杂度；PMD 口径用于与 PMD 做逐方法交叉验证。
     * 通过 {@code -Dcqm.complexity.profile=mccabe|pmd} 切换。
     */
    public enum ComplexityProfile {
        /** 经典 McCabe 约定：布尔路径任意位置计数，不计 throw，lambda 体计入外层方法。 */
        MCCABE("mccabe"),
        /** PMD 7 默认约定：布尔路径仅在条件位置计数，计 throw，忽略 lambda 体。 */
        PMD("pmd");

        public final String id;

        ComplexityProfile(String id) {
            this.id = id;
        }

        public static ComplexityProfile of(String s) {
            if (s != null) {
                for (ComplexityProfile p : values()) {
                    if (p.id.equalsIgnoreCase(s.trim()) || p.name().equalsIgnoreCase(s.trim())) {
                        return p;
                    }
                }
            }
            return MCCABE;
        }
    }

    /** 当前生效的复杂度口径，由系统属性 {@code cqm.complexity.profile} 指定。 */
    public static final ComplexityProfile PROFILE =
            ComplexityProfile.of(System.getProperty("cqm.complexity.profile", "mccabe"));

    /** 分析结果：方法指标 + 重复代码块 + 解析统计。 */
    public static final class Result {
        public final List<Models.StaticMethod> methods = new ArrayList<>();
        public final Map<String, List<String>> duplicates = new LinkedHashMap<>();
        public final String complexityProfile = PROFILE.id;
        public int filesParsed;
        public int filesFailed;
        public long elapsedMs;

        public int totalLoc() {
            return methods.stream().mapToInt(m -> m.loc).sum();
        }

        public double avgComplexity() {
            return methods.isEmpty() ? 0
                    : methods.stream().mapToInt(m -> m.complexity).average().orElse(0);
        }

        public int maxComplexity() {
            return methods.stream().mapToInt(m -> m.complexity).max().orElse(0);
        }
    }

    /** 资源类型：创建这些类型的对象后需要显式释放。 */
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
            "BufferedReader", "BufferedWriter", "InputStreamReader", "OutputStreamWriter",
            "RandomAccessFile", "Scanner", "PrintWriter", "FileChannel",
            "Socket", "ServerSocket", "ZipInputStream", "ZipOutputStream");

    /** 返回资源的工厂方法名。 */
    private static final Set<String> RESOURCE_METHODS = Set.of(
            "getConnection", "openConnection", "openStream", "open",
            "getInputStream", "getOutputStream", "newInputStream", "newOutputStream");

    private static final int DUP_WINDOW = 10;          // 连续重复行数阈值
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private StaticAnalyzer() {
    }

    /**
     * 分析一个源码目录（递归）。
     *
     * @param root 源码根目录
     * @return 分析结果；目录不存在时返回空结果
     */
    public static Result analyze(Path root) {
        long t0 = System.currentTimeMillis();
        Result result = new Result();
        if (root == null || !Files.isDirectory(root)) {
            System.err.println("[static] 源码目录不存在: " + root);
            result.elapsedMs = System.currentTimeMillis() - t0;
            return result;
        }

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setCharacterEncoding(StandardCharsets.UTF_8)
                // 不保留注释与词法信息，降低内存占用（本分析不需要注释内容）
                .setAttributeComments(false);
        JavaParser parser = new JavaParser(config);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException e) {
            System.err.println("[static] 遍历目录失败: " + e.getMessage());
        }
        Collections.sort(files);

        // 供重复代码检测使用的归一化行（按文件）
        List<String> allNormalized = new ArrayList<>();
        List<String> allLocations = new ArrayList<>();

        for (Path f : files) {
            String source;
            try {
                if (Files.size(f) > MAX_FILE_BYTES) {
                    continue;
                }
                source = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                result.filesFailed++;
                continue;
            }

            ParseResult<CompilationUnit> pr = parser.parse(source);
            if (!pr.isSuccessful() || pr.getResult().isEmpty()) {
                result.filesFailed++;
                System.err.println("[static] 解析失败，已跳过: " + root.relativize(f)
                        + "  " + pr.getProblems().stream().findFirst().map(Object::toString).orElse(""));
                continue;
            }
            result.filesParsed++;
            CompilationUnit cu = pr.getResult().get();
            String rel = normalizePath(root.relativize(f).toString());

            // 递归收集所有类型（含嵌套类）中的方法
            collectTypes(cu.getTypes(), rel, source, result.methods);

            // 归一化行：用于重复代码检测
            List<String> stripped = stripComments(source);
            for (int i = 0; i < stripped.size(); i++) {
                String s = stripped.get(i).replaceAll("\\s+", " ").trim();
                if (s.isEmpty() || s.equals("{") || s.equals("}")) {
                    continue;
                }
                allNormalized.add(s);
                allLocations.add(rel + ":" + (i + 1));
            }
        }

        detectDuplicates(allNormalized, allLocations, result.duplicates);
        result.elapsedMs = System.currentTimeMillis() - t0;
        System.out.printf("[static] 解析 %d 个文件（失败 %d），方法 %d 个，重复片段 %d 处，"
                        + "耗时 %d ms（复杂度口径 %s）%n",
                result.filesParsed, result.filesFailed, result.methods.size(),
                result.duplicates.size(), result.elapsedMs, PROFILE.id);
        return result;
    }

    // ───────────────────────── 类型与方法遍历 ─────────────────────────

    private static void collectTypes(NodeList<TypeDeclaration<?>> types, String file,
                                     String source, List<Models.StaticMethod> out) {
        for (TypeDeclaration<?> t : types) {
            collectType(t, file, source, out);
        }
    }

    private static void collectType(TypeDeclaration<?> type, String file, String source,
                                    List<Models.StaticMethod> out) {
        String typeName = qualify(type);

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration md) {
                out.add(analyzeMethod(typeName, md.getNameAsString(), md.getBody().orElse(null),
                        declLine(md), endLine(md), file, source));
                collectLocalTypes(md, typeName, file, source, out);
            } else if (member instanceof ConstructorDeclaration cd) {
                out.add(analyzeMethod(typeName, "<init>", cd.getBody(),
                        declLine(cd), endLine(cd), file, source));
                collectLocalTypes(cd, typeName, file, source, out);
            } else if (member instanceof TypeDeclaration<?> nested) {
                // 嵌套类：按其自身名称统计，不再归入外层类
                collectType(nested, file, source, out);
            } else if (member instanceof com.github.javaparser.ast.body.InitializerDeclaration init) {
                collectLocalTypes(init, typeName, file, source, out);
            }
        }
    }

    /**
     * 方法声明的行号。
     *
     * <p>必须取自方法名标识符而非节点整体范围：JavaParser 中
     * {@code MethodDeclaration} 的范围<b>包含其注解</b>，
     * 若直接用节点起始行，带 {@code @Override}、{@code @Deprecated} 等注解的方法
     * 会被定位到注解所在行，与 PMD 等工具（定位到声明行）产生偏差，
     * 也会让"违规位置"指向注解而非方法本身。构造方法同理。
     */
    private static int declLine(com.github.javaparser.ast.body.CallableDeclaration<?> decl) {
        return decl.getName().getRange().map(r -> r.begin.line).orElse(0);
    }

    private static int endLine(Node node) {
        return node.getRange().map(r -> r.end.line).orElse(0);
    }

    /**
     * 收集方法/构造器/初始化块内部的<b>局部类</b>中定义的方法。
     *
     * <p>局部类不是类型的成员，若只遍历 {@code getMembers()} 会被漏掉，
     * 导致这些方法既不作为独立条目出现、也不计入外层方法——形成统计盲区。
     * 匿名类中的方法同样在此收集，以 {@code Outer$anon} 命名。
     */
    private static void collectLocalTypes(Node owner, String typeName, String file,
                                          String source, List<Models.StaticMethod> out) {
        // 局部类（含方法体内声明的 class）
        for (ClassOrInterfaceDeclaration local
                : owner.findAll(ClassOrInterfaceDeclaration.class)) {
            collectType(local, file, source, out);
        }
        for (RecordDeclaration rec : owner.findAll(RecordDeclaration.class)) {
            collectType(rec, file, source, out);
        }
        // 匿名内部类：以 Outer$anon 命名
        for (ObjectCreationExpr oce : owner.findAll(ObjectCreationExpr.class)) {
            if (oce.getAnonymousClassBody().isEmpty()) {
                continue;
            }
            String anonName = typeName + "$anon";
            for (BodyDeclaration<?> member : oce.getAnonymousClassBody().get()) {
                if (member instanceof MethodDeclaration md) {
                    out.add(analyzeMethod(anonName, md.getNameAsString(),
                            md.getBody().orElse(null),
                            declLine(md), endLine(md), file, source));
                } else if (member instanceof TypeDeclaration<?> nested) {
                    collectType(nested, file, source, out);
                }
            }
        }
    }

    /** 生成限定类名：嵌套类用 Outer.Inner。 */
    private static String qualify(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder(type.getNameAsString());
        Node p = type.getParentNode().orElse(null);
        while (p != null) {
            if (p instanceof TypeDeclaration<?> pt) {
                sb.insert(0, pt.getNameAsString() + ".");
            }
            p = p.getParentNode().orElse(null);
        }
        return sb.toString();
    }

    // ───────────────────────── 单方法分析 ─────────────────────────

    private static Models.StaticMethod analyzeMethod(String className, String methodName,
                                                     Node body, int startLine, int endLine,
                                                     String file, String source) {
        int loc = countEffectiveLines(source, startLine, endLine);
        int complexity = body == null ? 1 : 1 + complexityOf(body, false);
        boolean emptyCatch = body != null && hasEmptyCatch(body);
        boolean unclosed = body != null && hasUnclosedResource(body);
        Models.StaticMethod m = new Models.StaticMethod(className, methodName, loc, complexity,
                emptyCatch, unclosed, startLine);
        m.file = file;
        return m;
    }

    /** 统计有效代码行数：行区间内剔除空行、纯注释行与单独的大括号行。 */
    private static int countEffectiveLines(String source, int startLine, int endLine) {
        if (startLine <= 0 || endLine < startLine) {
            return 0;
        }
        String[] lines = stripComments(source).toArray(new String[0]);
        int n = 0;
        for (int i = startLine - 1; i < Math.min(endLine, lines.length); i++) {
            String s = lines[i].trim();
            if (s.isEmpty() || s.equals("{") || s.equals("}")) {
                continue;
            }
            n++;
        }
        return n;
    }

    /**
     * 递归统计判定点个数。
     *
     * <p>必须正确划分"哪些判定点属于当前方法"：
     * <ul>
     *   <li><b>嵌套/局部类型声明</b>不向下递归——其中方法会作为独立条目统计；</li>
     *   <li><b>匿名内部类</b>的方法体同样单独统计，因此遇到匿名类时
     *       只递归其构造参数，不进入 {@code anonymousClassBody}。
     *       否则匿名类方法中的 {@code if} 会被同时计入外层方法与自身，造成重复计数；</li>
     *   <li><b>lambda 表达式</b>按口径决定：MCCABE 计入当前方法（避免复杂度丢失），
     *       PMD 口径忽略（与 PMD 行为一致）。</li>
     * </ul>
     *
     * <p>{@code inCondition} 表示当前节点是否处于"控制语句的条件位置"。
     * 判定规则：控制语句（if / while / do / for / 三元）只把其条件子节点标记为
     * 条件位置，其余子节点（then/else/循环体）一律为 false；
     * 非控制语句的子节点继承父节点的标记。这样 {@code if (a && b)} 中的
     * {@code &&} 处于条件位置，而 {@code return a && b} 中的不处于——
     * 正是 PMD 口径所要求的区别。
     */
    private static int complexityOf(Node node, boolean inCondition) {
        // 嵌套类型与嵌套方法体单独统计，不计入当前方法
        if (node instanceof TypeDeclaration
                || node instanceof MethodDeclaration
                || node instanceof ConstructorDeclaration) {
            return 0;
        }

        final boolean pmd = PROFILE == ComplexityProfile.PMD;
        int c = 0;
        if (node instanceof IfStmt
                || node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof WhileStmt
                || node instanceof DoStmt
                || node instanceof CatchClause
                || node instanceof ConditionalExpr) {
            c++;
        } else if (node instanceof SwitchEntry se) {
            // switch 的每个 case 分支计一次，default 不计
            if (!se.getLabels().isEmpty()) {
                c++;
            }
        } else if (node instanceof BinaryExpr be) {
            BinaryExpr.Operator op = be.getOperator();
            if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                // MCCABE 口径：任意位置的布尔路径都计一次；
                // PMD 口径：只有处于控制语句条件位置的布尔路径才计
                if (!pmd || inCondition) {
                    c++;
                }
            }
        } else if (pmd && node instanceof com.github.javaparser.ast.stmt.ThrowStmt) {
            // PMD 口径把 throw 视为控制流跳转，计一次
            c++;
        }

        // 匿名内部类：只统计构造参数，不进入其成员方法体
        if (node instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()) {
            if (oce.getScope().isPresent()) {
                c += complexityOf(oce.getScope().get(), inCondition);
            }
            for (com.github.javaparser.ast.expr.Expression arg : oce.getArguments()) {
                c += complexityOf(arg, inCondition);
            }
            return c;
        }

        // PMD 口径忽略 lambda 体内部的判定点
        if (pmd && node instanceof LambdaExpr) {
            return c;
        }

        boolean isCondOwner = node instanceof IfStmt || node instanceof WhileStmt
                || node instanceof DoStmt || node instanceof ForStmt
                || node instanceof ConditionalExpr;
        for (Node child : node.getChildNodes()) {
            boolean childInCond = isCondOwner
                    ? isConditionChild(node, child)
                    : inCondition;
            c += complexityOf(child, childInCond);
        }
        return c;
    }

    /** child 是否为父控制语句的"条件"子节点。 */
    private static boolean isConditionChild(Node parent, Node child) {
        if (parent instanceof IfStmt ifs) {
            return ifs.getCondition() == child;
        }
        if (parent instanceof WhileStmt ws) {
            return ws.getCondition() == child;
        }
        if (parent instanceof DoStmt ds) {
            return ds.getCondition() == child;
        }
        if (parent instanceof ForStmt fs) {
            return fs.getCompare().map(expr -> expr == child).orElse(false);
        }
        if (parent instanceof ConditionalExpr ce) {
            return ce.getCondition() == child;
        }
        return false;
    }

    /** 是否存在"捕获后未做任何处理"的 catch 块。 */
    private static boolean hasEmptyCatch(Node body) {
        for (CatchClause cc : body.findAll(CatchClause.class)) {
            if (cc.getBody().getStatements().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否打开了资源但未在异常路径释放。
     *
     * <p>判定规则（比"存在 close 调用即认为安全"更严格）：
     * <ol>
     *   <li>方法中存在资源创建表达式；且该创建不在 try-with-resources 的资源列表中；</li>
     *   <li>并且<b>不存在</b>位于 {@code finally} 块内的 {@code close()} 调用。</li>
     * </ol>
     * 只要资源创建受 try-with-resources 保护，或有 finally 兜底关闭，即视为已处理。
     * 仅在正常路径调用 {@code close()} 的方法会被判定为存在泄漏风险——
     * 这正是"资源未关闭"规则真正想捕捉的情形。
     *
     * <p>局限：不追踪具体是哪个资源被哪个 close 关闭（多资源混合场景可能漏判），
     * 也不分析方法调用链下游是否代关闭，该局限已在论文中说明。
     */
    private static boolean hasUnclosedResource(Node body) {
        boolean created = false;
        for (ObjectCreationExpr oce : body.findAll(ObjectCreationExpr.class)) {
            if (RESOURCE_TYPES.contains(oce.getType().getNameAsString())
                    && !insideTryResource(oce)) {
                created = true;
                break;
            }
        }
        if (!created) {
            for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
                if (RESOURCE_METHODS.contains(call.getNameAsString()) && !insideTryResource(call)) {
                    created = true;
                    break;
                }
            }
        }
        if (!created) {
            return false;
        }
        // 存在位于 finally 块中的 close() 才算有兜底释放
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if ("close".equals(call.getNameAsString()) && insideFinally(call)) {
                return false;
            }
        }
        return true;
    }

    /** 该节点是否位于某个 try 语句的 finally 块内。 */
    private static boolean insideFinally(Node node) {
        Node p = node.getParentNode().orElse(null);
        int guard = 0;
        while (p != null && guard++ < 64) {
            if (p instanceof com.github.javaparser.ast.stmt.BlockStmt block
                    && block.getParentNode().orElse(null) instanceof TryStmt ts
                    && ts.getFinallyBlock().isPresent()
                    && ts.getFinallyBlock().get() == block) {
                return true;
            }
            p = p.getParentNode().orElse(null);
        }
        return false;
    }

    /** 该节点是否位于某个 try-with-resources 的资源列表中。 */
    private static boolean insideTryResource(Node node) {
        Node p = node.getParentNode().orElse(null);
        int guard = 0;
        while (p != null && guard++ < 64) {
            if (p instanceof TryStmt ts) {
                for (var re : ts.getResources()) {
                    if (re == node || isDescendant(node, re)) {
                        return true;
                    }
                }
            }
            p = p.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isDescendant(Node node, Node ancestor) {
        Node p = node.getParentNode().orElse(null);
        int guard = 0;
        while (p != null && guard++ < 64) {
            if (p == ancestor) {
                return true;
            }
            p = p.getParentNode().orElse(null);
        }
        return false;
    }

    // ───────────────────────── 重复代码检测 ─────────────────────────

    private static void detectDuplicates(List<String> lines, List<String> locations,
                                         Map<String, List<String>> out) {
        if (lines.size() < DUP_WINDOW * 2) {
            return;
        }
        Map<String, List<String>> index = new HashMap<>();
        for (int i = 0; i + DUP_WINDOW <= lines.size(); i++) {
            StringBuilder sb = new StringBuilder(DUP_WINDOW * 40);
            for (int k = 0; k < DUP_WINDOW; k++) {
                sb.append(lines.get(i + k)).append('\n');
            }
            index.computeIfAbsent(sb.toString(), k -> new ArrayList<>())
                    .add(locations.get(i));
        }
        // 只保留确实重复的片段，并做去重（相邻窗口高度重叠，取首个即可）
        Set<String> seenStarts = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            if (e.getValue().size() > 1) {
                String start = e.getValue().get(0);
                if (seenStarts.add(start)) {
                    out.put(start, e.getValue());
                }
            }
        }
    }

    // ───────────────────────── 词法预处理 ─────────────────────────

    /**
     * 去掉块注释与行注释，并清空字符串/字符/文本块字面量的内容，保持行数不变。
     *
     * <p>该方法目前仅用于计算"有效代码行数"与归一化行：
     * 由于方法边界与复杂度已改由语法树决定，字面量中的括号不再影响正确性，
     * 但清空字面量仍可避免把字符串内容当成代码行计入。
     */
    static List<String> stripComments(String source) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        boolean inTextBlock = false;
        for (String raw : source.split("\r\n|\r|\n", -1)) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < raw.length()) {
                if (inTextBlock) {
                    int e = raw.indexOf("\"\"\"", i);
                    if (e < 0) {
                        i = raw.length();
                    } else {
                        inTextBlock = false;
                        sb.append("\"\"\"");
                        i = e + 3;
                    }
                    continue;
                }
                if (inBlock) {
                    int e = raw.indexOf("*/", i);
                    if (e < 0) {
                        i = raw.length();
                    } else {
                        inBlock = false;
                        i = e + 2;
                    }
                    continue;
                }
                if (i + 1 < raw.length() && raw.charAt(i) == '/' && raw.charAt(i + 1) == '*') {
                    inBlock = true;
                    i += 2;
                    continue;
                }
                if (i + 1 < raw.length() && raw.charAt(i) == '/' && raw.charAt(i + 1) == '/') {
                    break;
                }
                if (i + 2 < raw.length() && raw.startsWith("\"\"\"", i)) {
                    inTextBlock = true;
                    sb.append("\"\"\"");
                    i += 3;
                    continue;
                }
                char c = raw.charAt(i);
                if (c == '"' || c == '\'') {
                    char quote = c;
                    sb.append(quote);
                    i++;
                    while (i < raw.length()) {
                        char d = raw.charAt(i);
                        if (d == '\\' && i + 1 < raw.length()) {
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

    private static String normalizePath(String p) {
        return p.replace('\\', '/');
    }

    /** 供外部（如诊断脚本）复用的注释剥离入口。 */
    public static List<String> stripCommentsForTest(String source) {
        return stripComments(source);
    }

    /** 保留旧入口以兼容既有调用（返回结果中的方法列表）。 */
    public static List<Models.StaticMethod> analyzeMethods(Path root) {
        return analyze(root).methods;
    }

    static {
        // 触发一次自检，确保 JavaParser 在运行期可用
        try {
            Class.forName("com.github.javaparser.StaticJavaParser");
        } catch (Throwable t) {
            System.err.println("[static] 警告：未找到 JavaParser，静态分析将无法工作 -> " + t);
        }
    }
}
