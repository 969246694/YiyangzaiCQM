package com.cqm.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化层：基于 H2 嵌入式数据库，表结构与论文表 4-3 一致。
 *
 * <p>论文中提到工程化部署使用 MySQL；此处采用 H2 嵌入式数据库，
 * 优点是不需要独立部署数据库服务、单机即可运行演示，
 * 且 SQL 与表结构与 MySQL 基本兼容，迁移成本低。
 *
 * <p>表清单：
 * <ul>
 *   <li>{@code project}        被监控项目及接入配置</li>
 *   <li>{@code metric_raw}     运行期指标明细</li>
 *   <li>{@code metric_summary} 按日汇总的质量得分</li>
 *   <li>{@code static_metric}  静态分析结果（论文表 4-3 的扩展）</li>
 *   <li>{@code quality_rule}   质量规则定义</li>
 *   <li>{@code violation}      规则检测违规记录</li>
 *   <li>{@code alarm}          告警记录</li>
 *   <li>{@code coverage}       覆盖率统计（论文表 4-3 的扩展）</li>
 * </ul>
 */
public final class Db {

    private static final String URL =
            System.getProperty("cqm.db",
                    "jdbc:h2:file:./data/cqm;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");

    /** 明细表保留行数上限，超出后自动清理最旧记录（线上长期运行必须，避免磁盘被撑满）。 */
    private static final int KEEP_ROWS =
            Integer.getInteger("cqm.db.keepRows", 5000);

    private Db() {
    }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    // ───────────────────────── 建表 ─────────────────────────
    /** 建表语句：每条独立执行，避免一条失败导致后续表全部缺失。 */
    private static final String[] DDL = {
            "CREATE TABLE IF NOT EXISTS project("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128), "
                    + "package_prefix VARCHAR(256), status VARCHAR(32), created_at BIGINT)",
            "CREATE TABLE IF NOT EXISTS metric_raw("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, class_name VARCHAR(256), "
                    + "method_name VARCHAR(128), calls BIGINT, avg_millis DOUBLE, max_millis DOUBLE, "
                    + "errors BIGINT, err_rate DOUBLE, called_at BIGINT)",
            "CREATE TABLE IF NOT EXISTS metric_summary("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, module VARCHAR(256), "
                    + "score DOUBLE, stat_date VARCHAR(16))",
            "CREATE TABLE IF NOT EXISTS static_metric("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, class_name VARCHAR(256), "
                    + "method_name VARCHAR(128), loc INT, complexity INT, "
                    + "empty_catch BOOLEAN, unclosed_resource BOOLEAN, "
                    + "file VARCHAR(512), start_line INT)",
            "CREATE TABLE IF NOT EXISTS quality_rule("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128), target VARCHAR(64), "
                    + "cond VARCHAR(64), threshold DOUBLE, level VARCHAR(8))",
            "CREATE TABLE IF NOT EXISTS violation("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, rule_name VARCHAR(128), "
                    + "location VARCHAR(512), detail VARCHAR(512), level VARCHAR(8), created_at BIGINT)",
            // 注意：VALUE 是 H2 保留字，列名改用 metric_value
            "CREATE TABLE IF NOT EXISTS alarm("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, metric VARCHAR(128), "
                    + "metric_value DOUBLE, threshold DOUBLE, detail VARCHAR(512), created_at BIGINT)",
            "CREATE TABLE IF NOT EXISTS coverage_stat("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, project_id INT, instrumented INT, "
                    + "covered INT, ratio DOUBLE, stat_date VARCHAR(16))",

            // ── 兼容早期版本创建的库 ──
            // CREATE TABLE IF NOT EXISTS 只会创建新表，不会给已存在的表补列。
            // 升级时新增的字段必须用幂等的 ALTER 语句补上，否则新代码的读写会因
            // "列不存在"而全部失败——而且这个问题只在"升级既有库"时暴露，
            // 全新部署无法测出。此处同时补索引。
            "ALTER TABLE static_metric ADD COLUMN IF NOT EXISTS file VARCHAR(512)",
            "ALTER TABLE static_metric ADD COLUMN IF NOT EXISTS start_line INT",
            "CREATE INDEX IF NOT EXISTS idx_metric_project "
                    + "ON metric_raw(project_id, class_name, method_name)",
            "CREATE INDEX IF NOT EXISTS idx_violation_level ON violation(project_id, level)",
    };

    public static void init() {
        int ok = 0;
        try (Connection c = conn()) {
            for (String ddl : DDL) {
                try (Statement st = c.createStatement()) {
                    st.execute(ddl);
                    ok++;
                } catch (SQLException e) {
                    String tbl = ddl.replaceAll("(?s).*?EXISTS\\s+(\\w+).*", "$1");
                    System.err.println("[db] 建表失败 " + tbl + ": " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] 连接失败: " + e.getMessage());
        }
        System.out.println("[db] schema ready (" + ok + "/" + DDL.length + "): " + URL);
    }

    /** 幂等地登记项目。 */
    public static int ensureProject(String name, String packagePrefix) {
        try (Connection c = conn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM project WHERE name=?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO project(name,package_prefix,status,created_at) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, packagePrefix);
                ps.setString(3, "已接入");
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getInt(1) : 1;
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] ensureProject failed: " + e.getMessage());
            return 1;
        }
    }

    // ───────────────────────── 写入 ─────────────────────────
    public static void saveMetrics(int projectId, List<Models.Metric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO metric_raw(project_id,class_name,method_name,calls,avg_millis,"
                        + "max_millis,errors,err_rate,called_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (Models.Metric m : metrics) {
                ps.setInt(1, projectId);
                ps.setString(2, m.className);
                ps.setString(3, m.methodName);
                ps.setLong(4, m.calls);
                ps.setDouble(5, m.avgMillis);
                ps.setDouble(6, m.maxMillis);
                ps.setLong(7, m.errors);
                ps.setDouble(8, m.errRate);
                ps.setLong(9, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            System.err.println("[db] saveMetrics failed: " + e.getMessage());
        }
        prune("metric_raw");
    }

    /**
     * 保留明细表最新的 KEEP_ROWS 行，删除更早的记录。
     * 页面只展示每方法的最新指标，历史明细对展示无用，但会持续占用磁盘，
     * 因此长期运行时必须定期清理。
     */
    private static void prune(String table) {
        String sql = "DELETE FROM " + table + " WHERE id <= "
                + "(SELECT COALESCE(MAX(id), 0) - ? FROM " + table + ")";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, KEEP_ROWS);
            int n = ps.executeUpdate();
            if (n > 0) {
                System.out.println("[db] 清理 " + table + " 旧记录 " + n + " 行");
            }
        } catch (SQLException e) {
            System.err.println("[db] prune " + table + " failed: " + e.getMessage());
        }
    }

    public static void saveStaticMetrics(int projectId, List<Models.StaticMethod> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        try (Connection c = conn()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM static_metric WHERE project_id=?")) {
                del.setInt(1, projectId);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO static_metric(project_id,class_name,method_name,loc,complexity,"
                            + "empty_catch,unclosed_resource,file,start_line) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)")) {
                for (Models.StaticMethod m : list) {
                    ps.setInt(1, projectId);
                    ps.setString(2, m.className);
                    ps.setString(3, m.methodName);
                    ps.setInt(4, m.loc);
                    ps.setInt(5, m.complexity);
                    ps.setBoolean(6, m.emptyCatch);
                    ps.setBoolean(7, m.unclosedResource);
                    ps.setString(8, m.file == null ? "" : m.file);
                    ps.setInt(9, m.startLine);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            System.err.println("[db] saveStaticMetrics failed: " + e.getMessage());
        }
    }

    public static void saveViolations(int projectId, List<Models.Violation> list) {
        try (Connection c = conn()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM violation WHERE project_id=?")) {
                del.setInt(1, projectId);
                del.executeUpdate();
            }
            if (list == null || list.isEmpty()) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO violation(project_id,rule_name,location,detail,level,created_at)"
                            + " VALUES(?,?,?,?,?,?)")) {
                for (Models.Violation v : list) {
                    ps.setInt(1, projectId);
                    ps.setString(2, v.ruleName);
                    ps.setString(3, v.location);
                    ps.setString(4, v.detail);
                    ps.setString(5, v.level);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            System.err.println("[db] saveViolations failed: " + e.getMessage());
        }
    }

    public static void saveAlarms(int projectId, List<Models.Alarm> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO alarm(project_id,metric,metric_value,threshold,detail,created_at)"
                        + " VALUES(?,?,?,?,?,?)")) {
            for (Models.Alarm a : list) {
                ps.setInt(1, projectId);
                ps.setString(2, a.metric);
                ps.setDouble(3, a.value);
                ps.setDouble(4, a.threshold);
                ps.setString(5, a.detail);
                ps.setLong(6, a.createdAt);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            System.err.println("[db] saveAlarms failed: " + e.getMessage());
        }
        prune("alarm");
    }

    public static void saveScore(int projectId, double score, String date) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO metric_summary(project_id,module,score,stat_date) VALUES(?,?,?,?)")) {
            ps.setInt(1, projectId);
            ps.setString(2, "整体");
            ps.setDouble(3, score);
            ps.setString(4, date);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[db] saveScore failed: " + e.getMessage());
        }
    }

    public static void saveCoverage(int projectId, int instrumented, int covered, double ratio,
                                    String date) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO coverage_stat(project_id,instrumented,covered,ratio,stat_date)"
                        + " VALUES(?,?,?,?,?)")) {
            ps.setInt(1, projectId);
            ps.setInt(2, instrumented);
            ps.setInt(3, covered);
            ps.setDouble(4, ratio);
            ps.setString(5, date);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[db] saveCoverage failed: " + e.getMessage());
        }
    }

    // ───────────────────────── 查询（供报告与页面使用）─────────────────────────
    /** 最近一次上报的每个方法指标（按 class#method 去重取最新）。 */
    public static List<Models.Metric> latestMetrics(int projectId) {
        Map<String, Models.Metric> map = new LinkedHashMap<>();
        String sql = "SELECT class_name,method_name,calls,avg_millis,max_millis,errors,err_rate"
                + " FROM metric_raw WHERE project_id=? ORDER BY id";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1) + "#" + rs.getString(2);
                    Models.Metric m = map.get(key);
                    if (m == null) {
                        m = new Models.Metric();
                        map.put(key, m);
                    }
                    m.className = rs.getString(1);
                    m.methodName = rs.getString(2);
                    m.calls = rs.getLong(3);
                    m.avgMillis = rs.getDouble(4);
                    m.maxMillis = rs.getDouble(5);
                    m.errors = rs.getLong(6);
                    m.errRate = rs.getDouble(7);
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] latestMetrics failed: " + e.getMessage());
        }
        return new ArrayList<>(map.values());
    }

    public static List<Models.StaticMethod> staticMetrics(int projectId) {
        List<Models.StaticMethod> out = new ArrayList<>();
        String sql = "SELECT class_name,method_name,loc,complexity,empty_catch,unclosed_resource,"
                + "file,start_line FROM static_metric WHERE project_id=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Models.StaticMethod m = new Models.StaticMethod(rs.getString(1), rs.getString(2),
                            rs.getInt(3), rs.getInt(4), rs.getBoolean(5), rs.getBoolean(6),
                            rs.getInt(8));
                    String f = rs.getString(7);
                    m.file = f == null ? "" : f;
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] staticMetrics failed: " + e.getMessage());
        }
        return out;
    }

    public static List<Models.Violation> violations(int projectId) {
        List<Models.Violation> out = new ArrayList<>();
        String sql = "SELECT rule_name,location,detail,level FROM violation WHERE project_id=?"
                + " ORDER BY id";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Models.Violation(rs.getString(1), "", rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] violations failed: " + e.getMessage());
        }
        return out;
    }

    public static List<Models.Alarm> alarms(int projectId, int limit) {
        List<Models.Alarm> out = new ArrayList<>();
        String sql = "SELECT metric,metric_value,threshold,detail FROM alarm WHERE project_id=?"
                + " ORDER BY id DESC LIMIT ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Models.Alarm a = new Models.Alarm(rs.getString(1), rs.getDouble(2),
                            rs.getDouble(3), rs.getString(4));
                    out.add(a);
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] alarms failed: " + e.getMessage());
        }
        return out;
    }

    public static List<double[]> scoreHistory(int projectId, int limit) {
        List<double[]> out = new ArrayList<>();
        String sql = "SELECT score FROM metric_summary WHERE project_id=? ORDER BY id DESC LIMIT ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new double[]{rs.getDouble(1)});
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] scoreHistory failed: " + e.getMessage());
        }
        return out;
    }

    public static int[] latestCoverage(int projectId) {
        String sql = "SELECT instrumented,covered FROM coverage_stat WHERE project_id=?"
                + " ORDER BY id DESC LIMIT 1";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new int[]{rs.getInt(1), rs.getInt(2)};
                }
            }
        } catch (SQLException e) {
            System.err.println("[db] latestCoverage failed: " + e.getMessage());
        }
        return new int[]{0, 0};
    }
}
