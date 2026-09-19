-- V7__event_delivery.sql — 事件投递登记与死信表（P1 册 4.3.14，DDL 逐字对齐设计册）
--
-- 反向 SQL（Flyway Community 无 undo，回滚 = 前向补偿脚本，需另写 V<n> 并走评审）：
--   DROP TABLE eaio_platform.event_delivery;
--   DELETE FROM flyway_schema_history WHERE version = '7';
-- 数据不可逆点：`event_delivery` 是"业务事务内登记的投递事实"——删表即丢失全部未完成的重投与
-- 待人工处置的死信；业务数据本身不受影响（业务写与登记同事务，但登记不是业务的唯一记录），
-- 相反被删掉的登记无法重建：`payload_json` 是事件载荷的唯一快照。
--
-- 约定（P1 册 4.3 与批次总册 3.5）：
--   * 表名 snake_case 单数、模块内不加前缀（Schema 已隔离）；
--   * 统一列 id/created_at/created_by/updated_at/updated_by/version 只适用于**可变元数据表**；
--     本表是**显式的例外**（4.3 的例外清单，1278 行）：有 updated_*/version，但**没有 deleted**
--     ——投递记录不做逻辑删除：`DONE` 按保留天数物理清理、`DEAD` 保留到人工处置（3.9.3），
--     "删除"这条语义在状态机里由 status 表达，再加一列 deleted 只会让两个真相打架；
--   * 时间列 TIMESTAMPTZ；枚举用 VARCHAR(32) + CHECK（禁 PG ENUM / 数组）；
--   * 索引命名 pk_/uk_/idx_；DDL 显式 schema 限定；
--   * `payload_json` 是 JSONB（事件载荷无固定结构），由 V3 引入的 JsonbStringTypeHandler 绑定。
--
-- 三条硬口径（3.9.2）在本表上的落点：
--   1. 登记与业务**同事务**：行在业务事务内 INSERT（status=RETRYING、attempt_count=1、
--      next_retry_time=NULL），业务回滚则登记一起回滚 —— 这就是"轻量 outbox"；
--   2. 提交后同步投递：成功 → DONE + finish_time；任一监听器抛异常 → attempt_count+1、
--      next_retry_time = now + platform.event.retry-backoff-seconds × 2^(attempt-1)（封顶 30min）、
--      last_error 落地（不留悬空行）；
--   3. 超过 max_attempt → DEAD，不再被扫描器捞出（idx_event_delivery_status_retry 只服务重投）。
--
-- event_id 上的唯一索引是**插件的幂等兜底**：消费侧按 eventId 去重（5.5），同一事件被重复登记时
-- 数据库直接拒绝（而不是悄悄多一条投递记录再投两次）。

CREATE TABLE eaio_platform.event_delivery (
    id              BIGINT       NOT NULL,
    event_id        VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload_json    JSONB        NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'RETRYING',
    attempt_count   INT          NOT NULL DEFAULT 1,
    max_attempt     INT          NOT NULL DEFAULT 5,
    next_retry_time TIMESTAMPTZ,
    last_error      TEXT,
    trace_id        VARCHAR(64),
    finish_time     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    version         INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_event_delivery PRIMARY KEY (id),
    CONSTRAINT ck_event_delivery_status CHECK (status IN ('RETRYING', 'DONE', 'DEAD'))
);
CREATE UNIQUE INDEX uk_event_delivery_event_id ON eaio_platform.event_delivery (event_id);
CREATE INDEX idx_event_delivery_status_retry ON eaio_platform.event_delivery (status, next_retry_time);
CREATE INDEX idx_event_delivery_type ON eaio_platform.event_delivery (event_type);
COMMENT ON TABLE eaio_platform.event_delivery IS '事件投递登记（与业务同事务写入）+ 重试 + 死信；event_id 是消费侧幂等键';
COMMENT ON COLUMN eaio_platform.event_delivery.payload_json IS '事件载荷快照（用于重投）：只放 ID 与标量，禁止放文件内容或密钥';
