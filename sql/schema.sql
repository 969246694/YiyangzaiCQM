-- ============================================================================
--  代码质量监控平台 —— 数据库建表脚本
--  对应论文表 4-3《主要数据表设计》
--
--  说明：
--    1. 本平台的服务端以 H2 嵌入式数据库运行，程序启动时会自动建表；
--       本脚本用于手工部署、查阅表结构，或迁移到 MySQL 时作为参考。
--    2. 语法为通用 SQL，在 H2 与 MySQL 上均可执行；
--       迁移到 MySQL 时建议补充 ENGINE=InnoDB DEFAULT CHARSET=utf8mb4。
--    3. 字段命名避免了 VALUE 等数据库保留字（告警值使用 metric_value）。
-- ============================================================================

-- 1. 被监控项目及探针接入配置
CREATE TABLE IF NOT EXISTS project (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128)  NOT NULL,           -- 项目名称（探针 -Dcqm.app 上报）
    package_prefix  VARCHAR(256),                     -- 被插桩的包前缀
    status          VARCHAR(32),                      -- 接入状态
    created_at      BIGINT                            -- 接入时间（毫秒时间戳）
);

-- 2. 运行期指标明细（探针上报并聚合后的方法级数据）
CREATE TABLE IF NOT EXISTS metric_raw (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    project_id   INT,
    class_name   VARCHAR(256),                        -- 类名（全限定名）
    method_name  VARCHAR(128),                        -- 方法名
    calls        BIGINT,                              -- 累计调用次数
    avg_millis   DOUBLE,                              -- 平均耗时（毫秒）
    max_millis   DOUBLE,                              -- 最大耗时（毫秒）
    errors       BIGINT,                              -- 异常次数
    err_rate     DOUBLE,                              -- 异常率（百分数）
    called_at    BIGINT,                              -- 上报时间（毫秒时间戳）
    CONSTRAINT fk_metric_project FOREIGN KEY (project_id) REFERENCES project (id)
);
CREATE INDEX IF NOT EXISTS idx_metric_project ON metric_raw (project_id, class_name, method_name);
CREATE INDEX IF NOT EXISTS idx_metric_time    ON metric_raw (called_at);

-- 3. 按日汇总的质量得分（用于趋势分析）
CREATE TABLE IF NOT EXISTS metric_summary (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    project_id  INT,
    module      VARCHAR(256),                         -- 模块（本平台按整体汇总）
    score       DOUBLE,                               -- 质量得分 0~100
    stat_date   VARCHAR(16),                          -- 统计日期 yyyy-MM-dd
    CONSTRAINT fk_summary_project FOREIGN KEY (project_id) REFERENCES project (id)
);

-- 4. 静态代码分析结果（论文表 4-3 的扩展表）
CREATE TABLE IF NOT EXISTS static_metric (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    project_id         INT,
    class_name         VARCHAR(256),
    method_name        VARCHAR(128),
    loc                INT,                           -- 有效代码行数
    complexity         INT,                           -- 圈复杂度（McCabe）
    empty_catch        BOOLEAN,                       -- 是否存在空 catch 块
    unclosed_resource  BOOLEAN,                       -- 是否存在未关闭的资源
    CONSTRAINT fk_static_project FOREIGN KEY (project_id) REFERENCES project (id)
);

-- 5. 质量规则定义（对应论文表 4-2 的 6 条规则）
CREATE TABLE IF NOT EXISTS quality_rule (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128),                          -- 规则名称
    target     VARCHAR(64),                           -- 判定对象（方法 / 代码块）
    cond       VARCHAR(64),                           -- 判定条件描述
    threshold  DOUBLE,                                -- 阈值
    level      VARCHAR(8)                             -- 严重等级（高 / 中 / 低）
);

-- 6. 规则检测违规记录
CREATE TABLE IF NOT EXISTS violation (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    project_id  INT,
    rule_name   VARCHAR(128),
    location    VARCHAR(512),                         -- 违规位置（类#方法）
    detail      VARCHAR(512),                         -- 违规说明
    level       VARCHAR(8),
    created_at  BIGINT,
    CONSTRAINT fk_violation_project FOREIGN KEY (project_id) REFERENCES project (id)
);
CREATE INDEX IF NOT EXISTS idx_violation_project ON violation (project_id, level);

-- 7. 告警记录（阈值越界）
--    注意：列名使用 metric_value，因为 VALUE 是 SQL 保留字
CREATE TABLE IF NOT EXISTS alarm (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    project_id    INT,
    metric        VARCHAR(128),                       -- 触发告警的指标名称
    metric_value  DOUBLE,                             -- 实际值
    threshold     DOUBLE,                             -- 阈值
    detail        VARCHAR(512),
    created_at    BIGINT,
    CONSTRAINT fk_alarm_project FOREIGN KEY (project_id) REFERENCES project (id)
);

-- 8. 方法覆盖率统计（论文表 4-3 的扩展表）
--    覆盖率 = 实际被调用过的方法数 / 已插桩方法总数
CREATE TABLE IF NOT EXISTS coverage_stat (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    project_id    INT,
    instrumented  INT,                                -- 已插桩方法数
    covered       INT,                                -- 实际被调用过的方法数
    ratio         DOUBLE,                             -- 覆盖率（百分数）
    stat_date     VARCHAR(16),
    CONSTRAINT fk_coverage_project FOREIGN KEY (project_id) REFERENCES project (id)
);

-- ============================================================================
--  预置数据：论文表 4-2 定义的 6 条质量规则
--  （服务端在代码中以常量方式内置同样的定义，此处供数据库侧查阅与调整）
-- ============================================================================
INSERT INTO quality_rule (name, target, cond, threshold, level) VALUES
    ('方法复杂度过高', '方法',   '圈复杂度 > 15',                      15,  '高'),
    ('方法体过长',     '方法',   '有效代码行数 > 80',                  80,  '中'),
    ('重复代码块',     '代码块', '连续重复行数 > 10',                  10,  '中'),
    ('异常被忽略',     '方法',   '捕获异常后无任何处理',                0,  '高'),
    ('资源未关闭',     '方法',   '打开流或连接后异常路径未释放',        0,  '高'),
    ('热点方法耗时偏高', '方法', '平均耗时 > 100ms 且调用次数 > 1000', 100, '中');
