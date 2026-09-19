-- V2__param_center.sql — 参数配置中心两张表（P1 册 4.3.1 / 4.3.2，DDL 逐字对齐设计册）
--
-- 反向 SQL（Flyway Community 无 undo，回滚 = 前向补偿脚本，需另写 V10 并走评审）：
--   DROP TABLE eaio_platform.param_change_log;
--   DROP TABLE eaio_platform.param;
--
-- 约定（P1 册 4.3 与批次总册 3.5）：
--   * 表名 snake_case 单数、模块内不加前缀（Schema 已隔离）；
--   * 统一列 id/created_at/created_by/updated_at/updated_by/version/deleted；id 为雪花 ID（非自增）；
--   * 时间列 TIMESTAMPTZ；布尔 BOOLEAN；枚举用 VARCHAR(32) + CHECK（禁 PG ENUM / 数组）；
--   * 索引命名 idx_/uk_/fk_；DDL 显式 schema 限定；"看名字猜不出语义"的表与列写 COMMENT ON；
--   * param_change_log 是**只追加**表：按统一列的显式例外省略 updated_at/updated_by/version/deleted。

CREATE TABLE eaio_platform.param (
    id          BIGINT       NOT NULL,
    param_key   VARCHAR(128) NOT NULL,
    param_level VARCHAR(32)  NOT NULL,
    owner_id    BIGINT       NOT NULL DEFAULT 0,
    param_value TEXT,
    value_type  VARCHAR(32)  NOT NULL DEFAULT 'STRING',
    param_group VARCHAR(64)  NOT NULL DEFAULT 'default',
    encrypted   BOOLEAN      NOT NULL DEFAULT false,
    builtin     BOOLEAN      NOT NULL DEFAULT false,
    hot_reload  BOOLEAN      NOT NULL DEFAULT true,
    remark      VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_at  TIMESTAMPTZ,
    updated_by  BIGINT,
    version     INT          NOT NULL DEFAULT 0,
    deleted     BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT pk_param PRIMARY KEY (id),
    CONSTRAINT ck_param_level CHECK (param_level IN ('SYSTEM', 'ORG', 'USER')),
    CONSTRAINT ck_param_value_type CHECK (value_type IN ('STRING', 'INT', 'BOOL', 'DECIMAL', 'JSON', 'SECRET')),
    CONSTRAINT ck_param_owner CHECK ((param_level = 'SYSTEM' AND owner_id = 0) OR (param_level <> 'SYSTEM' AND owner_id > 0))
);
CREATE UNIQUE INDEX uk_param_key_level_owner ON eaio_platform.param (param_key, param_level, owner_id) WHERE deleted = false;
CREATE INDEX idx_param_group ON eaio_platform.param (param_group);
CREATE INDEX idx_param_level_owner ON eaio_platform.param (param_level, owner_id);
COMMENT ON TABLE eaio_platform.param IS '参数中心：分级配置（USER > ORG > SYSTEM 覆盖）；SECRET 类值为 AES-GCM 密文，接口不回显明文';
COMMENT ON COLUMN eaio_platform.param.owner_id IS '归属 ID：SYSTEM 级恒为 0，ORG 级为组织 ID，USER 级为用户 ID';
COMMENT ON COLUMN eaio_platform.param.hot_reload IS 'false 表示改值需重启才生效（前端提示"重启生效"）';

CREATE TABLE eaio_platform.param_change_log (
    id          BIGINT       NOT NULL,
    param_id    BIGINT       NOT NULL,
    param_key   VARCHAR(128) NOT NULL,
    param_level VARCHAR(32)  NOT NULL,
    owner_id    BIGINT       NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    operator_id BIGINT,
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    CONSTRAINT pk_param_change_log PRIMARY KEY (id)
);
CREATE INDEX idx_param_change_log_key_time ON eaio_platform.param_change_log (param_key, created_at DESC);
COMMENT ON TABLE eaio_platform.param_change_log IS '参数变更历史（只追加，不更新不删除）；SECRET 参数的 old/new_value 记 ****** 而非明文';
