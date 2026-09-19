-- V5__scheduler.sql — 定时任务三张表（P1 册 4.3.9 / 4.3.10 / 4.3.11，DDL 逐字对齐设计册）
--
-- 反向 SQL（Flyway Community 无 undo，回滚 = 前向补偿脚本，需另写 V<n> 并走评审）：
--   DROP TABLE eaio_platform.job_run;
--   DROP TABLE eaio_platform.job;
--   DROP TABLE eaio_platform.shedlock;
--   DELETE FROM flyway_schema_history WHERE version = '5';
-- 数据不可逆点：`job_run` 是**只追加**的运行事实（4.3.10），删表即丢失全部执行历史；
-- 反过来 `job` 表丢了可以靠种子重新种（R__platform_seed.sql 的 6 个内置任务）+ 业务模块重新 register。
--
-- 约定（P1 册 4.3 与批次总册 3.5）：
--   * 表名 snake_case 单数、模块内不加前缀（Schema 已隔离）；
--   * 统一列 id/created_at/created_by/updated_at/updated_by/version/deleted 只适用于**可变元数据表**
--     （本脚本里就是 `job`）；
--   * 时间列 TIMESTAMPTZ；布尔 BOOLEAN；枚举用 VARCHAR(32) + CHECK（禁 PG ENUM / 数组）；
--   * 索引命名 idx_/uk_/fk_；DDL 显式 schema 限定；
--   * 三类**刻意的例外**，都写在 4.3 的册面口径里：
--     1. `job_run` 没有 updated_*/version/deleted —— 它是只追加的运行日志，没有"更新语义"，
--        也就不需要乐观锁；DELETE 由 platform.job.log.clean 按保留天数分批物理删除（3.4.4）；
--     2. `shedlock` 是 **ShedLock JDBC provider 的契约表**：列名/类型/主键由 provider 生成的 SQL 决定
--        （`INSERT ... ON CONFLICT (name) DO UPDATE`、`lock_until`/`locked_at`/`locked_by`），
--        加一个字或改一个类型都会让锁在运行期直接报错，因此**不加**统一列（4.3.11 明写的例外）；
--     3. `job.params_json` 是 JSONB（任务参数无固定结构），由 V3 引入的 JsonbStringTypeHandler 绑定。
--
-- ShedLock 版本（**与设计册口径的差异，登记在《实现注记（T7）》**）：册面 3.4.6 写 6.6.0，实测 6.x 依赖
-- Spring 6（Boot 4.1 是 Spring 7），故本仓锁 **7.10.1**（`net.javacrumbs.shedlock:shedlock-core` +
-- `shedlock-provider-jdbc-template`，JDBC template provider + `usingDbTime()`）。
-- provider 在 DB 时间内模式下生成的 SQL 用 `timezone('utc', CURRENT_TIMESTAMP)` 与
-- `make_interval(secs => :lockAtMostForInterval)` 表达"锁到什么时候"，**不往时间列里绑参数**，
-- 因此三列取 TIMESTAMPTZ 与写进来的 UTC 值语义一致；这也让锁的判定只用数据库时钟，
-- 应用与库的时钟漂移不会让锁提前失效（3.4.6 的 `usingDbTime()` 理由）。

CREATE TABLE eaio_platform.job (
    id               BIGINT       NOT NULL,
    job_code         VARCHAR(64)  NOT NULL,
    job_name         VARCHAR(128) NOT NULL,
    handler_code     VARCHAR(128) NOT NULL,
    cron             VARCHAR(64)  NOT NULL,
    params_json      JSONB,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    timeout_seconds  INT          NOT NULL DEFAULT 300,
    retry_max        INT          NOT NULL DEFAULT 3,
    backoff_seconds  INT          NOT NULL DEFAULT 30,
    allow_concurrent BOOLEAN      NOT NULL DEFAULT false,
    next_run_time    TIMESTAMPTZ,
    last_run_time    TIMESTAMPTZ,
    last_status      VARCHAR(32),
    remark           VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    version          INT          NOT NULL DEFAULT 0,
    deleted          BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT pk_job PRIMARY KEY (id),
    CONSTRAINT ck_job_timeout CHECK (timeout_seconds > 0),
    CONSTRAINT ck_job_retry CHECK (retry_max >= 0 AND retry_max <= 10),
    CONSTRAINT ck_job_last_status CHECK (last_status IS NULL OR last_status IN ('SUCCESS', 'FAILED', 'TIMEOUT', 'SKIPPED'))
);
CREATE UNIQUE INDEX uk_job_code ON eaio_platform.job (job_code) WHERE deleted = false;
CREATE INDEX idx_job_enabled ON eaio_platform.job (enabled);
COMMENT ON COLUMN eaio_platform.job.handler_code IS '必须命中 JobHandlerRegistry（代码注册的可执行点），否则保存/启用报 20023；禁止反射调用任意 Bean 方法';
COMMENT ON COLUMN eaio_platform.job.next_run_time IS '仅供管理页展示（执行后回写）；调度决策以内存中的调度器为准，不用本列';

CREATE TABLE eaio_platform.job_run (
    id              BIGINT      NOT NULL,
    job_code        VARCHAR(64) NOT NULL,
    trigger_type    VARCHAR(32) NOT NULL DEFAULT 'CRON',
    status          VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    attempt         INT         NOT NULL DEFAULT 1,
    start_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    end_time        TIMESTAMPTZ,
    duration_ms     INT,
    next_retry_time TIMESTAMPTZ,
    node_id         VARCHAR(64),
    trace_id        VARCHAR(64),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    CONSTRAINT pk_job_run PRIMARY KEY (id),
    CONSTRAINT ck_job_run_trigger CHECK (trigger_type IN ('CRON', 'MANUAL', 'RETRY')),
    CONSTRAINT ck_job_run_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'RETRYING', 'SKIPPED'))
);
CREATE INDEX idx_job_run_code_start ON eaio_platform.job_run (job_code, start_time DESC);
CREATE INDEX idx_job_run_status_start ON eaio_platform.job_run (status, start_time);
CREATE INDEX idx_job_run_retry ON eaio_platform.job_run (next_retry_time) WHERE status = 'RETRYING';
COMMENT ON COLUMN eaio_platform.job_run.error_message IS '截断到 2000 字符：错误信息可能含业务数据，不落完整堆栈';

CREATE TABLE eaio_platform.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
COMMENT ON TABLE eaio_platform.shedlock IS 'ShedLock JDBC provider 约定表：列名/类型由 provider 的 SQL 决定，禁止加列改名（统一列口径的显式例外，4.1）';
