CREATE TABLE eaio_platform.alert_rule (
    id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    metric_key VARCHAR(128) NOT NULL,
    operator VARCHAR(32) NOT NULL,
    threshold NUMERIC(18,4) NOT NULL,
    duration_seconds INT NOT NULL DEFAULT 0,
    severity VARCHAR(32) NOT NULL DEFAULT 'WARN',
    silence_seconds INT NOT NULL DEFAULT 1800,
    notify_site BOOLEAN NOT NULL DEFAULT true,
    enabled BOOLEAN NOT NULL DEFAULT true,
    builtin BOOLEAN NOT NULL DEFAULT false,
    remark VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT pk_alert_rule PRIMARY KEY (id),
    CONSTRAINT ck_alert_rule_operator CHECK (operator IN ('GT','GTE','LT','LTE','EQ')),
    CONSTRAINT ck_alert_rule_severity CHECK (severity IN ('INFO','WARN','CRITICAL'))
);
CREATE UNIQUE INDEX uk_alert_rule_code ON eaio_platform.alert_rule (rule_code) WHERE deleted = false;
CREATE INDEX idx_alert_rule_enabled ON eaio_platform.alert_rule (enabled);

CREATE TABLE eaio_platform.alert (
    id BIGINT NOT NULL,
    rule_id BIGINT,
    rule_code VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    title VARCHAR(255) NOT NULL,
    detail TEXT,
    metric_value NUMERIC(18,4),
    threshold_value NUMERIC(18,4),
    first_trigger_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_trigger_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    trigger_count INT NOT NULL DEFAULT 1,
    ack_by BIGINT,
    ack_time TIMESTAMPTZ,
    resolve_time TIMESTAMPTZ,
    notice_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_alert PRIMARY KEY (id),
    CONSTRAINT ck_alert_status CHECK (status IN ('OPEN','ACKED','RESOLVED')),
    CONSTRAINT ck_alert_severity CHECK (severity IN ('INFO','WARN','CRITICAL')),
    CONSTRAINT fk_alert_rule FOREIGN KEY (rule_id) REFERENCES eaio_platform.alert_rule (id),
    CONSTRAINT fk_alert_notice FOREIGN KEY (notice_id) REFERENCES eaio_platform.notice (id)
);
CREATE INDEX idx_alert_status_severity ON eaio_platform.alert (status, severity, last_trigger_time DESC);
CREATE INDEX idx_alert_rule_time ON eaio_platform.alert (rule_code, first_trigger_time DESC);
-- 多实例评估时只允许同一规则存在一个 OPEN 告警；冲突由 INSERT ... DO NOTHING 转为计数累加。
CREATE UNIQUE INDEX uk_alert_open_rule ON eaio_platform.alert (rule_code) WHERE status = 'OPEN';
