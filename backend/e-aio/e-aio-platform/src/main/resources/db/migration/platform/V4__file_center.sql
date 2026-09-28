-- V4__file_center.sql — 文件中心四张表（P1 册 4.3.5 / 4.3.6 / 4.3.7 / 4.3.8，DDL 逐字对齐设计册）
--
-- 反向 SQL（Flyway Community 无 undo，回滚 = 前向补偿脚本，需另写 V<n> 并走评审）：
--   DROP TABLE eaio_platform.file_binding;
--   DROP TABLE eaio_platform.file_chunk;
--   DROP TABLE eaio_platform.file_upload_session;
--   DROP TABLE eaio_platform.file;
--   （顺序即依赖顺序：file 是 file_binding 与 file_upload_session 的父表）
--
-- 约定同 V3（P1 册 4.3 与批次总册 3.5）：snake_case 单数表名、雪花 id 非自增、
-- 统一列 created_at/created_by/updated_at/updated_by/version/deleted、TIMESTAMPTZ、VARCHAR+CHECK 枚举。
--
-- 本票（T9）只落地**单文件**路径；表仍按 4.3.6/4.3.7 一次性建好（分片上传是 #22 的范围）：
-- 迁移脚本一旦发布不可修改（checksum），先建表比让 #22 再补一个 V 脚本便宜。
--
-- 统一列（批次总册 3.5）与 4.2 的**例外清单**逐表对照，别一张表一套口径：
--   * file / file_upload_session：按 3.5 的统一列口径补齐 —— 4.3.6 的 DDL 没写 `deleted`
--     （4.3.5 的 file 写了），这里补上 BOOLEAN NOT NULL DEFAULT false。file_upload_session 尤其需要：
--     过期会话行的清理与"行保留 session-retain-days"都靠逻辑删除语义；
--   * file_chunk：只追加表，按 4.2 的统一列例外省略 `updated_*`/`version`/`deleted`；
--   * file_binding：**不加** `deleted` —— 4.2 的统一列例外清单明确把它列为"关系行，解绑即物理删"。
--     该条比 3.5 的通用口径更具体，按"更具体者优先"适用例外；P1 也没有解绑接口，逻辑删除列只会是死列。
--     因此唯一键就是 4.3.8 原样的 uk_file_binding_triple(file_id, biz_type, biz_id)（**非**部分索引）。
--   * 4.3.6 未给"保留天数清理"的排序列 → 建 idx_file_upload_session_status_created(status, created_at)，
--     让 `platform.file.session.expire` 的按保留期分批删有索引可走（20000 行的表全表扫也能跑，
--     但清理任务每 10 分钟跑一次，不值得让它在热表上扫）。

CREATE TABLE eaio_platform.file (
    id              BIGINT       NOT NULL,
    original_name   VARCHAR(255) NOT NULL,
    extension       VARCHAR(32),
    content_type    VARCHAR(128),
    size_bytes      BIGINT       NOT NULL,
    sha256          CHAR(64)     NOT NULL,
    storage_type    VARCHAR(32)  NOT NULL DEFAULT 'LOCAL',
    storage_path    VARCHAR(512) NOT NULL,
    uploader_id     BIGINT,
    uploader_org_id BIGINT       NOT NULL DEFAULT 0,
    source          VARCHAR(32)  NOT NULL DEFAULT 'UPLOAD',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    version         INT          NOT NULL DEFAULT 0,
    deleted         BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT pk_file PRIMARY KEY (id),
    CONSTRAINT ck_file_storage_type CHECK (storage_type IN ('LOCAL', 'S3')),
    CONSTRAINT ck_file_source CHECK (source IN ('UPLOAD', 'CHUNK', 'EXPORT', 'IMPORT_ERROR', 'TEMPLATE')),
    CONSTRAINT ck_file_size CHECK (size_bytes >= 0)
);
CREATE INDEX idx_file_sha256 ON eaio_platform.file (sha256);
CREATE INDEX idx_file_uploader ON eaio_platform.file (uploader_id);
CREATE INDEX idx_file_org ON eaio_platform.file (uploader_org_id);
CREATE INDEX idx_file_created_at ON eaio_platform.file (created_at);
-- 清理任务的两个扫描口（3.3.7）：孤儿软删按"创建时间 + 无绑定"，物理删按"软删时间"。
-- 部分索引只覆盖各自要扫的行，比全表索引小一个量级。
CREATE INDEX idx_file_deleted_at ON eaio_platform.file (updated_at) WHERE deleted = true;
CREATE INDEX idx_file_orphan ON eaio_platform.file (created_at) WHERE deleted = false;
COMMENT ON TABLE eaio_platform.file IS '文件元数据；二进制在存储适配器（本地盘/S3），库内只存元数据与存储路径';
COMMENT ON COLUMN eaio_platform.file.storage_type IS '写入后不可变：下载/删除按行选择适配器（P1-C5），否则切换存储会导致历史文件读不到';
COMMENT ON COLUMN eaio_platform.file.sha256 IS '服务端重算的摘要（不信任客户端）：只用于完整性校验与导入任务冲突检测，不做秒传/物理去重（3.3.7）';

CREATE TABLE eaio_platform.file_upload_session (
    id            BIGINT       NOT NULL,
    upload_id     VARCHAR(64)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    extension     VARCHAR(32),
    expected_size BIGINT       NOT NULL,
    chunk_size    INT          NOT NULL,
    chunk_total   INT          NOT NULL,
    sha256        CHAR(64),
    storage_type  VARCHAR(32)  NOT NULL DEFAULT 'LOCAL',
    status        VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    expire_time   TIMESTAMPTZ  NOT NULL,
    file_id       BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ,
    updated_by    BIGINT,
    version       INT          NOT NULL DEFAULT 0,
    deleted       BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT pk_file_upload_session PRIMARY KEY (id),
    CONSTRAINT ck_file_upload_session_status CHECK (status IN ('OPEN', 'MERGING', 'DONE', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_file_upload_session_chunk CHECK (chunk_total > 0 AND chunk_size > 0),
    CONSTRAINT fk_file_upload_session_file FOREIGN KEY (file_id) REFERENCES eaio_platform.file (id)
);
CREATE UNIQUE INDEX uk_file_upload_session_upload_id ON eaio_platform.file_upload_session (upload_id);
CREATE INDEX idx_file_upload_session_status_expire ON eaio_platform.file_upload_session (status, expire_time);
CREATE INDEX idx_file_upload_session_status_created ON eaio_platform.file_upload_session (status, created_at);
COMMENT ON COLUMN eaio_platform.file_upload_session.upload_id IS '客户端生成的 UUID；与 chunk_index 一起构成"重传同一片"的幂等身份（3.3.3）';

CREATE TABLE eaio_platform.file_chunk (
    id           BIGINT       NOT NULL,
    session_id   BIGINT       NOT NULL,
    chunk_index  INT          NOT NULL,
    chunk_size   INT          NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    CONSTRAINT pk_file_chunk PRIMARY KEY (id),
    CONSTRAINT ck_file_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT fk_file_chunk_session FOREIGN KEY (session_id) REFERENCES eaio_platform.file_upload_session (id)
);
CREATE UNIQUE INDEX uk_file_chunk_session_index ON eaio_platform.file_chunk (session_id, chunk_index);
COMMENT ON TABLE eaio_platform.file_chunk IS '分片元数据；唯一键 (session_id, chunk_index) 使"重传同一片"天然幂等（UPSERT 覆盖写）';

CREATE TABLE eaio_platform.file_binding (
    id         BIGINT      NOT NULL,
    file_id    BIGINT      NOT NULL,
    biz_type   VARCHAR(64) NOT NULL,
    biz_id     BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    CONSTRAINT pk_file_binding PRIMARY KEY (id),
    CONSTRAINT fk_file_binding_file FOREIGN KEY (file_id) REFERENCES eaio_platform.file (id)
);
CREATE UNIQUE INDEX uk_file_binding_triple ON eaio_platform.file_binding (file_id, biz_type, biz_id);
CREATE INDEX idx_file_binding_biz ON eaio_platform.file_binding (biz_type, biz_id);
COMMENT ON COLUMN eaio_platform.file_binding.biz_type IS '业务类型后缀（如 crm.customer）；platform 不解释其含义，只做索引';
