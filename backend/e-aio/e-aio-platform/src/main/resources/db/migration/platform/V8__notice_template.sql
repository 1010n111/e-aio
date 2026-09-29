-- P1 T12 公告与通知模板
CREATE TABLE eaio_platform.notice (
    id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    scope_type VARCHAR(32) NOT NULL DEFAULT 'ALL',
    publish_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    publish_time TIMESTAMPTZ,
    expire_time TIMESTAMPTZ,
    top_flag BOOLEAN NOT NULL DEFAULT false,
    publisher_id BIGINT,
    publisher_org_id BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT pk_notice PRIMARY KEY (id),
    CONSTRAINT ck_notice_scope CHECK (scope_type IN ('ALL', 'ORG', 'USER')),
    CONSTRAINT ck_notice_publish_status CHECK (publish_status IN ('DRAFT', 'PUBLISHED', 'REVOKED')),
    CONSTRAINT ck_notice_expire CHECK (expire_time IS NULL OR publish_time IS NULL OR expire_time > publish_time)
);
CREATE INDEX idx_notice_publish_status ON eaio_platform.notice (publish_status, publish_time DESC);
CREATE INDEX idx_notice_expire_time ON eaio_platform.notice (expire_time) WHERE publish_status = 'PUBLISHED';

CREATE TABLE eaio_platform.notice_target (
    id BIGINT NOT NULL,
    notice_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    CONSTRAINT pk_notice_target PRIMARY KEY (id),
    CONSTRAINT ck_notice_target_type CHECK (target_type IN ('ORG', 'USER')),
    CONSTRAINT fk_notice_target_notice FOREIGN KEY (notice_id) REFERENCES eaio_platform.notice (id)
);
CREATE UNIQUE INDEX uk_notice_target_triple ON eaio_platform.notice_target (notice_id, target_type, target_id);
CREATE INDEX idx_notice_target_lookup ON eaio_platform.notice_target (target_type, target_id, notice_id);

CREATE TABLE eaio_platform.notice_read (
    id BIGINT NOT NULL,
    notice_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    CONSTRAINT pk_notice_read PRIMARY KEY (id),
    CONSTRAINT fk_notice_read_notice FOREIGN KEY (notice_id) REFERENCES eaio_platform.notice (id)
);
CREATE UNIQUE INDEX uk_notice_read_notice_user ON eaio_platform.notice_read (notice_id, user_id);

CREATE TABLE eaio_platform.notify_template (
    id BIGINT NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL DEFAULT 'SITE',
    title_template VARCHAR(255),
    content_template TEXT NOT NULL,
    variables_json JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED',
    builtin BOOLEAN NOT NULL DEFAULT false,
    remark VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT pk_notify_template PRIMARY KEY (id),
    CONSTRAINT ck_notify_template_channel CHECK (channel IN ('SITE', 'EMAIL', 'SMS')),
    CONSTRAINT ck_notify_template_status CHECK (status IN ('ENABLED', 'DISABLED'))
);
CREATE UNIQUE INDEX uk_notify_template_code ON eaio_platform.notify_template (template_code) WHERE deleted = false;
CREATE INDEX idx_notify_template_channel ON eaio_platform.notify_template (channel, status);
