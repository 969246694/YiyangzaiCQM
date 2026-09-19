package com.cqm.server;

import java.nio.file.Paths;

/** 交付审计用的临时驱动：跑静态分析并把结果原样打印出来。 */
public final class AuditRunner {
    public static void main(String[] args) {
        StaticAnalyzer.Result r = StaticAnalyzer.analyze(Paths.get(args[0]));
        System.out.println("== 解析文件 " + r.filesParsed + " 个（失败 " + r.filesFailed
                + "），方法 " + r.methods.size() + " 个，耗时 " + r.elapsedMs + " ms ==");
        System.out.println(String.format("%-26s %-26s %-22s %5s %6s %-8s %-8s",
                "类", "方法", "文件:行", "LOC", "复杂度", "空catch", "资源未关闭"));
        for (Models.StaticMethod m : r.methods) {
            System.out.println(String.format("%-26s %-26s %-22s %5d %6d %-8s %-8s",
                    m.className, m.methodName, m.file + ":" + m.startLine, m.loc, m.complexity,
                    m.emptyCatch ? "是" : "-", m.unclosedResource ? "是" : "-"));
        }
        System.out.println("== 重复代码块: " + r.duplicates.size() + " 处 ==");
        for (var e : r.duplicates.entrySet()) {
            System.out.println("   " + e.getKey() + " -> " + e.getValue());
        }
    }
}
