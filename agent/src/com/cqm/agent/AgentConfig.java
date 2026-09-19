package com.cqm.agent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 探针配置：通过 -D 系统属性注入，避免侵入业务代码。
 *
 * <pre>
 *   -Dcqm.packages=com.demo           被监控的包前缀（逗号分隔）
 *   -Dcqm.exclude=com.cqm,net.bytebuddy,java.,javax.,sun.,jdk.
 *   -Dcqm.server=http://127.0.0.1:8080
 *   -Dcqm.interval=5000               上报周期（毫秒）
 *   -Dcqm.sample=1.0                  细粒度采样率（0~1）
 *   -Dcqm.debug=false
 * </pre>
 */
public final class AgentConfig {

    private static final Set<String> INCLUDE = new HashSet<>();
    private static final Set<String> EXCLUDE = new HashSet<>();

    public static final String SERVER;
    public static final long INTERVAL_MS;
    public static final double SAMPLE;
    public static final boolean DEBUG;

    static {
        addAll(INCLUDE, System.getProperty("cqm.packages", "com.demo"));
        addAll(EXCLUDE, System.getProperty("cqm.exclude",
                "com.cqm,net.bytebuddy,java.,javax.,sun.,jdk.,com.sun."));
        SERVER = System.getProperty("cqm.server", "http://127.0.0.1:8080");
        INTERVAL_MS = Long.getLong("cqm.interval", 5000L);
        SAMPLE = Double.parseDouble(System.getProperty("cqm.sample", "1.0"));
        DEBUG = Boolean.parseBoolean(System.getProperty("cqm.debug", "false"));
    }

    private AgentConfig() {
    }

    private static void addAll(Set<String> target, String csv) {
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(target::add);
    }

    /** 是否需要对某个类插桩：命中包含前缀且不命中任何排除前缀。 */
    public static boolean shouldInstrument(String className) {
        for (String ex : EXCLUDE) {
            if (className.startsWith(ex)) {
                return false;
            }
        }
        for (String in : INCLUDE) {
            if (className.startsWith(in)) {
                return true;
            }
        }
        return false;
    }

    public static void debug(String msg) {
        if (DEBUG) {
            System.out.println("[cqm-agent] " + msg);
        }
    }
}
