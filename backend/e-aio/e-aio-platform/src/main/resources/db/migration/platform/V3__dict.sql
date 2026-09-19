-- V3__dict.sql — 数据字典两张表（P1 册 4.3.3 / 4.3.4，DDL 逐字对齐设计册）
--
-- 反向 SQL（Flyway Community 无 undo，回滚 = 前向补偿脚本，需另写 V<n> 并走评审）：
--   DROP TABLE eaio_platform.dict_item;
--   DROP TABLE eaio_platform.dict_type;
--
-- 约定（P1 册 4.3 与批次总册 3.5）：
--   * 表名 snake_case 单数、模块内不加前缀（Schema 已隔离）；
--   * 统一列 id/created_at/created_by/updated_at/updated_by/version/deleted；id 为雪花 ID（非自增）；
--   * 时间列 TIMESTAMPTZ；布尔 BOOLEAN；枚举用 VARCHAR(32) + CHECK（禁 PG ENUM / 数组）；
--   * 索引命名 idx_/uk_/fk_；DDL 显式 schema 限定；
--   * 唯一性是**部分唯一索引**（WHERE deleted = false）：允许"删除后重建同名 code"，代价是
--     "类型 → 项"不能建物理外键（部分唯一索引不能作 FK 目标），改由应用层校验（20003，3.2.4）。

CREATE TABLE eaio_platform.dict_type (
    id         BIGINT       NOT NULL,
    type_code  VARCHAR(64)  NOT NULL,
    type_name  VARCHAR(128) NOT NULL,
    status     VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    builtin    BOOLEAN      NOT NULL DEFAULT false,
    remark     VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version    INT          NOT NULL DEFAULT 0,
    deleted    BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT pk_dict_type PRIMARY KEY (id),
    CONSTRAINT ck_dict_type_status CHECK (status IN ('ENABLED', 'DISABLED'))
);
CREATE UNIQUE INDEX uk_dict_type_code ON eaio_platform.dict_type (type_code) WHERE deleted = false;
CREATE INDEX idx_dict_type_status ON eaio_platform.dict_type (status);
COMMENT ON TABLE eaio_platform.dict_type IS '字典类型；内置类型 builtin=true 不可删除';

CREATE TABLE eaio_platform.dict_item (
    id         BIGINT       NOT NULL,
    type_code  VARCHAR(64)  NOT NULL,
    item_value VARCHAR(128) NOT NULL,
    item_label VARCHAR(128) NOT NULL,
    sort_no    INT          NOT NULL DEFAULT 0,
    status     VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
    is_default BOOLEAN      NOT NULL DEFAULT false,
    ext_json   JSONB,
    remark     VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version    INT          NOT NULL DEFAULT 0,
    deleted    BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT pk_dict_item PRIMARY KEY (id),
    CONSTRAINT ck_dict_item_status CHECK (status IN ('ENABLED', 'DISABLED'))
);
CREATE UNIQUE INDEX uk_dict_item_type_value ON eaio_platform.dict_item (type_code, item_value) WHERE deleted = false;
CREATE INDEX idx_dict_item_type_sort ON eaio_platform.dict_item (type_code, sort_no);
COMMENT ON COLUMN eaio_platform.dict_item.ext_json IS '前端渲染扩展（颜色/图标等）；不为每种扩展加列（3.2.4）';
