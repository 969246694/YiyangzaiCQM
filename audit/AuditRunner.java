package com.cqm.server;

import java.nio.file.Paths;
import java.util.List;

/** 交付审计用的临时驱动：跑静态分析并把结果原样打印出来。 */
public final class AuditRunner {
    public static void main(String[] args) {
        List<Models.StaticMethod> list = StaticAnalyzer.analyze(Paths.get(args[0]));
        System.out.println("== 分析到 " + list.size() + " 个方法 ==");
        System.out.println(String.format("%-28s %-24s %5s %6s %-8s %-8s",
                "类", "方法", "LOC", "复杂度", "空catch", "资源未关闭"));
        for (Models.StaticMethod m : list) {
            System.out.println(String.format("%-28s %-24s %5d %6d %-8s %-8s",
                    m.className, m.methodName, m.loc, m.complexity,
                    m.emptyCatch ? "是" : "-", m.unclosedResource ? "是" : "-"));
        }
        System.out.println("== 重复代码块: " + StaticAnalyzer.DUP_BLOCKS.size() + " 处 ==");
    }
}
