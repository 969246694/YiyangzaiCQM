package diag;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * 诊断用最小探针：只对 com.demo.BizService.simpleQuery 插桩，
 * 打印进入/退出，用于确认 Byte Buddy 插桩链路本身是否正常。
 */
public class DiagAgent {

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[diag] premain start");
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.nameContains("BizService"))
                .transform((builder, td, cl, m, pd) -> {
                    System.out.println("[diag] transforming " + td.getName() + " loader=" + cl);
                    return builder.visit(Advice.to(DiagAdvice.class)
                            .on(ElementMatchers.named("simpleQuery")));
                })
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(net.bytebuddy.description.type.TypeDescription td,
                                                 ClassLoader cl, net.bytebuddy.utility.JavaModule mod,
                                                 boolean loaded,
                                                 net.bytebuddy.dynamic.DynamicType dt) {
                        System.out.println("[diag] onTransformation " + td.getName());
                    }

                    @Override
                    public void onError(String typeName, ClassLoader cl,
                                        net.bytebuddy.utility.JavaModule mod, boolean loaded,
                                        Throwable t) {
                        System.out.println("[diag] onError " + typeName + " -> " + t);
                        t.printStackTrace(System.out);
                    }

                    @Override
                    public void onDiscovery(String typeName, ClassLoader cl,
                                            net.bytebuddy.utility.JavaModule mod, boolean loaded) {
                        if (typeName.contains("BizService")) {
                            System.out.println("[diag] onDiscovery " + typeName + " loaded=" + loaded);
                        }
                    }
                })
                .installOn(inst);
        System.out.println("[diag] installed");
    }

    public static class DiagAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#t.#m") String where) {
            System.out.println("[diag] ENTER " + where);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Origin("#t.#m") String where) {
            System.out.println("[diag] EXIT " + where);
        }
    }
}
