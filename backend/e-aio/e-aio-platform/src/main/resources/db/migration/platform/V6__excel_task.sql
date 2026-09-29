-- Excel asynchronous import/export tasks and bounded row-level errors (P1 T11).
CREATE TABLE eaio_platform.excel_task (
    id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    biz_type VARCHAR(64) NOT NULL,
    template_code VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    progress_percent SMALLINT NOT NULL DEFAULT 0,
    total_rows INT NOT NULL DEFAULT 0,
    success_rows INT NOT NULL DEFAULT 0,
    fail_rows INT NOT NULL DEFAULT 0,
    deduplicated BOOLEAN NOT NULL DEFAULT false,
    source_file_id BIGINT,
    result_file_id BIGINT,
    error_file_id BIGINT,
    request_json JSONB,
    submitter_id BIGINT,
    submitter_org_id BIGINT NOT NULL DEFAULT 0,
    source_sha256 CHAR(64),
    start_time TIMESTAMPTZ,
    finish_time TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ,
    updated_by BIGINT,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_excel_task PRIMARY KEY (id),
    CONSTRAINT ck_excel_task_type CHECK (task_type IN ('IMPORT', 'EXPORT')),
    CONSTRAINT ck_excel_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_excel_task_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT fk_excel_task_source_file FOREIGN KEY (source_file_id) REFERENCES eaio_platform.file(id),
    CONSTRAINT fk_excel_task_result_file FOREIGN KEY (result_file_id) REFERENCES eaio_platform.file(id),
    CONSTRAINT fk_excel_task_error_file FOREIGN KEY (error_file_id) REFERENCES eaio_platform.file(id)
);
CREATE INDEX idx_excel_task_submitter_status ON eaio_platform.excel_task (submitter_id, status);
CREATE INDEX idx_excel_task_status_created ON eaio_platform.excel_task (status, created_at);
CREATE INDEX idx_excel_task_conflict ON eaio_platform.excel_task (task_type, biz_type, submitter_id, source_sha256)
    WHERE status IN ('PENDING', 'RUNNING');
COMMENT ON TABLE eaio_platform.excel_task IS 'Excel 异步导入导出任务；长任务以 taskId 轮询交付';
COMMENT ON COLUMN eaio_platform.excel_task.request_json IS '导出条件或导入参数摘要，禁止放密钥';

CREATE TABLE eaio_platform.excel_task_error (
    id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    row_num INT NOT NULL,
    column_name VARCHAR(128),
    cell_value VARCHAR(512),
    error_message VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    CONSTRAINT pk_excel_task_error PRIMARY KEY (id),
    CONSTRAINT fk_excel_task_error_task FOREIGN KEY (task_id) REFERENCES eaio_platform.excel_task(id)
);
CREATE INDEX idx_excel_task_error_task_row ON eaio_platform.excel_task_error (task_id, row_num);
COMMENT ON TABLE eaio_platform.excel_task_error IS 'Excel 导入行级错误明细，最多保留配置的前 N 条';
COMMENT ON COLUMN eaio_platform.excel_task_error.cell_value IS '单元格值截断到 512 字符，可能含敏感数据';
