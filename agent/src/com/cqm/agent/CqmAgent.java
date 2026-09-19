package com.cqm.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * 探针入口：Java Agent 的 premain / agentmain。
 *
 * <p>对应论文 5.2.1 Java Agent 接入实现：
 * 应用启动时通过 {@code -javaagent:cqm-agent.jar} 挂载，premain 在业务主方法之前执行，
 * 注册 ClassFileTransformer；此处使用 Byte Buddy 的 AgentBuilder 完成类匹配与方法插桩。
 */
public final class CqmAgent {

    private CqmAgent() {
    }

    /** JVM 启动时挂载（-javaagent 方式）。 */
    public static void premain(String args, Instrumentation inst) {
        install(args, inst);
    }

    /** 运行期动态挂载（VirtualMachine.attach 方式）。 */
    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst);
    }

    private static void install(String args, Instrumentation inst) {
        System.out.println("[cqm-agent] loading... include=" + System.getProperty("cqm.packages", "com.demo")
                + " server=" + AgentConfig.SERVER);

        // 关键：把"插桩运行时辅助类"追加到引导类加载器。
        // 插桩代码被内联进业务类后由业务类的类加载器执行，若辅助类只在系统类
        // 加载器中可见，业务类加载器无法解析，插桩代码会抛 NoClassDefFoundError。
        // 注意：只能追加仅含辅助类的 bootstrap.jar；若把含 Byte Buddy 的整个探针
        // JAR 追加进去，Byte Buddy 会被引导与系统两个加载器各自加载，
        // 触发 loader constraint violation。
        appendBootstrapHelpers(inst);

        // 关键：在插桩之前先把聚合器解析到引导类加载器中的那一份，
        // 保证"插桩代码写入的"与"上报线程读取的"是同一个静态状态。
        try {
            Aggregator.init();
        } catch (Throwable t) {
            System.err.println("[cqm-agent] FATAL: aggregator init failed: " + t);
        }

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.nameStartsWith("com.demo"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    // 只有配置命中的包才真正插桩，避免采集无关代码
                    if (!AgentConfig.shouldInstrument(typeDescription.getName())) {
                        return builder;
                    }
                    AgentConfig.debug("instrumenting " + typeDescription.getName());
                    try {
                        // 关键：必须排除构造方法（<init>）与静态初始化块（<clinit>）。
                        // Byte Buddy 无法在构造器调用中插入异常捕获，若匹配到构造方法会抛出
                        // "Cannot catch exception during constructor call"，导致整个类插桩失败。
                        net.bytebuddy.matcher.ElementMatcher.Junction<
                                net.bytebuddy.description.method.MethodDescription> methodFilter =
                                not(isAbstract()).and(not(isNative())).and(not(isSynthetic()))
                                        .and(not(ElementMatchers.isConstructor()))
                                        .and(not(ElementMatchers.isTypeInitializer()))
                                        .and(any());

                        // 登记将被插桩的方法，用于后续计算方法覆盖率
                        // （覆盖率 = 实际被调用过的方法数 / 已插桩方法总数）
                        for (net.bytebuddy.description.method.MethodDescription md
                                : typeDescription.getDeclaredMethods()) {
                            if (methodFilter.matches(md)) {
                                Aggregator.registerInstrumented(typeDescription.getName(), md.getName());
                            }
                        }

                        return builder.visit(Advice.to(TimingAdvice.class).on(methodFilter));
                    } catch (Throwable t) {
                        System.err.println("[cqm-agent] advice install failed on "
                                + typeDescription.getName() + ": " + t);
                        t.printStackTrace(System.err);
                        return builder;
                    }
                })
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(net.bytebuddy.description.type.TypeDescription td,
                                                 ClassLoader cl, net.bytebuddy.utility.JavaModule mod,
                                                 boolean loaded,
                                                 net.bytebuddy.dynamic.DynamicType dt) {
                        AgentConfig.debug("transformed " + td.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader cl,
                                        net.bytebuddy.utility.JavaModule mod, boolean loaded,
                                        Throwable t) {
                        System.err.println("[cqm-agent] instrument error on " + typeName + ": " + t);
                        t.printStackTrace(System.err);
                    }
                })
                .installOn(inst);

        Thread reporter = new Thread(new Reporter(AgentConfig.SERVER, AgentConfig.INTERVAL_MS),
                "cqm-reporter");
        reporter.setDaemon(true);
        reporter.start();
        System.out.println("[cqm-agent] installed, reporter interval=" + AgentConfig.INTERVAL_MS + "ms");
    }

    /** 将仅含辅助类的 bootstrap.jar 追加到引导类加载器搜索路径。 */
    private static void appendBootstrapHelpers(Instrumentation inst) {
        try {
            java.io.File self = new java.io.File(
                    CqmAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            java.io.File helper = new java.io.File(self.getParentFile(), "cqm-agent-bootstrap.jar");
            if (!helper.isFile()) {
                System.err.println("[cqm-agent] WARN: bootstrap helper jar not found: " + helper);
                return;
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(helper));
            System.out.println("[cqm-agent] bootstrap helpers appended: " + helper.getName());
        } catch (Throwable t) {
            System.err.println("[cqm-agent] WARN: append bootstrap helpers failed: " + t);
        }
    }
}
