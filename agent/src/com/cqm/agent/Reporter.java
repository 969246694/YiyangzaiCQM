package com.cqm.agent;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 上报线程：从聚合器取出快照，异步批量发送到服务端。
 *
 * <p>对应论文 4.2.3 抖动抑制设计的第三项措施——异步批量上报：
 * 独立线程按固定周期上报，业务线程只做内存累加，不被网络 IO 阻塞；
 * 上报失败时静默重试，不影响业务应用。
 *
 * <p>注意：聚合器位于引导类加载器，编译期不可直接引用其类型，
 * 故通过 {@link Aggregator} 门面以反射方式读取快照。
 */
public final class Reporter implements Runnable {

    private final String server;
    private final long intervalMs;

    public Reporter(String server, long intervalMs) {
        this.server = server;
        this.intervalMs = intervalMs;
    }

    @Override
    public void run() {
        AgentConfig.debug("reporter started, server=" + server + ", interval=" + intervalMs + "ms");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
                List<?> snapshots = Aggregator.drain();
                if (snapshots.isEmpty()) {
                    AgentConfig.debug("no metrics in this period");
                    continue;
                }
                String json = toJson(snapshots);
                post(json);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                AgentConfig.debug("report failed: " + t);
            }
        }
    }

    private static String toJson(List<?> list) throws Exception {
        StringBuilder sb = new StringBuilder(256 + list.size() * 200);
        sb.append("{\"app\":\"").append(escape(System.getProperty("cqm.app", "demo-app")))
                .append("\",\"startTime\":").append(Aggregator.startTime())
                // 已插桩方法总数，服务端用它与"有调用记录的方法数"相除得到方法覆盖率
                .append(",\"instrumented\":").append(Aggregator.instrumentedCount())
                .append(",\"metrics\":[");
        for (int i = 0; i < list.size(); i++) {
            Object s = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            long calls = (Long) Aggregator.field(s, "calls");
            long totalNanos = (Long) Aggregator.field(s, "totalNanos");
            double avgMillis = calls == 0 ? 0d : (totalNanos / 1_000_000d) / calls;
            sb.append("{\"className\":\"").append(escape((String) Aggregator.field(s, "className")))
                    .append("\",\"methodName\":\"").append(escape((String) Aggregator.field(s, "methodName")))
                    .append("\",\"calls\":").append(calls)
                    .append(",\"totalNanos\":").append(totalNanos)
                    .append(",\"errors\":").append(Aggregator.field(s, "errors"))
                    .append(",\"maxNanos\":").append(Aggregator.field(s, "maxNanos"))
                    .append(",\"minNanos\":").append(Aggregator.field(s, "minNanos"))
                    .append(",\"avgMillis\":").append(String.format("%.4f", avgMillis))
                    .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void post(String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(server + "/api/metrics").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(3000);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        // 上报属于写操作，服务端启用令牌校验时必须携带，否则会被拒绝（401）。
        // 令牌通过 -Dcqm.token=<值> 注入，两侧配置一致即可。
        String token = System.getProperty("cqm.token", "");
        if (!token.isEmpty()) {
            conn.setRequestProperty("X-CQM-Token", token);
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        if (code == 401) {
            System.err.println("[cqm-agent] 上报被拒绝（401）：探针令牌与服务端不一致，"
                    + "请检查 -Dcqm.token 配置");
        }
        AgentConfig.debug("reported " + json.length() + " bytes, http " + code + ", methods=" + count(json));
        conn.disconnect();
    }

    private static int count(String json) {
        int n = 0;
        int i = -1;
        while ((i = json.indexOf("\"className\":", i + 1)) >= 0) {
            n++;
        }
        return n;
    }
}
