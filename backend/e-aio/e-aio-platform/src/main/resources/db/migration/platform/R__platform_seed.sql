-- R__platform_seed.sql — 平台种子数据（P1 册 4.5）
--
-- 幂等：全部 INSERT 以 ON CONFLICT DO NOTHING 结尾，**不覆盖用户改过的值**；重复执行不报错。
-- 为什么用可重复迁移（R__）：种子要能"补新键"（每次发版可能新增参数）。代价是**改默认值不会自动生效**
-- ——默认值变更必须另写 V<n>__ 脚本显式 UPDATE（并同步代码内默认值），否则"改了种子但环境上没变"。
--
-- 固定字面量 ID（区间 1–9999，雪花 ID 远大于此，不会碰撞）；created_by = 0 表示系统。
-- 本脚本 P1 T3 落地参数中心的 10 条系统参数（键、默认值、类型、热更新口径见 P1 册 7.2；完整 31 条键里
-- 其余键由各自能力的票（任务/Excel/缓存/告警/公告）落地时追加到这里，不预置空壳）。
-- builtin = true：平台自带参数，管理页不允许删除（删即 20005 PARAM_BUILTIN_READONLY）。

INSERT INTO eaio_platform.param
    (id, param_key, param_level, owner_id, param_value, value_type, param_group, encrypted, builtin, hot_reload, created_by)
VALUES
    (1, 'platform.time.display-zone', 'SYSTEM', 0, 'Asia/Shanghai', 'STRING', 'time', false, true, true, 0),
    (2, 'platform.file.max-size', 'SYSTEM', 0, '52428800', 'INT', 'file', false, true, true, 0),
    (3, 'platform.file.allowed-ext', 'SYSTEM', 0,
        'jpg,jpeg,png,gif,webp,bmp,pdf,doc,docx,xls,xlsx,ppt,pptx,txt,csv,zip,7z', 'STRING', 'file', false, true, true, 0),
    (4, 'platform.file.chunk-size', 'SYSTEM', 0, '5242880', 'INT', 'file', false, true, true, 0),
    (5, 'platform.file.local-root', 'SYSTEM', 0, '${user.home}/.eaio/files', 'STRING', 'file', false, true, false, 0),
    (6, 'platform.file.session-ttl-hours', 'SYSTEM', 0, '24', 'INT', 'file', false, true, true, 0),
    (7, 'platform.excel.atomic-max-rows', 'SYSTEM', 0, '20000', 'INT', 'excel', false, true, true, 0),
    (8, 'platform.excel.max-import-rows', 'SYSTEM', 0, '200000', 'INT', 'excel', false, true, true, 0),
    (9, 'platform.excel.max-export-rows', 'SYSTEM', 0, '1000000', 'INT', 'excel', false, true, true, 0),
    (10, 'platform.excel.error-max', 'SYSTEM', 0, '1000', 'INT', 'excel', false, true, true, 0)
ON CONFLICT DO NOTHING;

-- P1 T5 落地数据字典的 4 个平台内置类型 + 15 个字典项（P1 册 4.5；册面写"14 项"，逐项枚举实为
-- 3+3+6+3 = 15 项，按枚举落地并登记在《实现注记（T5）》）。ID 区间：类型 21–30、项 31–60
-- （与参数的 1–10 不冲突，雪花 ID 远大于 9999）。
-- builtin = true：平台自带类型，管理页不允许删除（删即 20008 之前先被 builtin 拦下）。
-- sort_no 以 10 为步长，留出中间插入的余地；is_default/ext_json 册面未指定，一律取默认值。
INSERT INTO eaio_platform.dict_type
    (id, type_code, type_name, status, builtin, remark, created_by)
VALUES
    (21, 'platform_param_level', '参数级别', 'ENABLED', true, '参数中心的分级（P1 册 3.1.1）', 0),
    (22, 'platform_job_trigger_type', '任务触发方式', 'ENABLED', true, '定时任务的触发来源（P1 册 3.4）', 0),
    (23, 'platform_excel_task_status', 'Excel 任务状态', 'ENABLED', true, '导入导出任务的状态机（P1 册 3.5）', 0),
    (24, 'platform_alert_severity', '告警级别', 'ENABLED', true, '告警规则的严重级别（P1 册 3.8）', 0)
ON CONFLICT DO NOTHING;

INSERT INTO eaio_platform.dict_item
    (id, type_code, item_value, item_label, sort_no, status, is_default, created_by)
VALUES
    (31, 'platform_param_level', 'SYSTEM', '系统级', 10, 'ENABLED', false, 0),
    (32, 'platform_param_level', 'ORG', '组织级', 20, 'ENABLED', false, 0),
    (33, 'platform_param_level', 'USER', '用户级', 30, 'ENABLED', false, 0),
    (34, 'platform_job_trigger_type', 'CRON', '定时触发', 10, 'ENABLED', false, 0),
    (35, 'platform_job_trigger_type', 'MANUAL', '手动触发', 20, 'ENABLED', false, 0),
    (36, 'platform_job_trigger_type', 'RETRY', '失败重试', 30, 'ENABLED', false, 0),
    (37, 'platform_excel_task_status', 'PENDING', '待执行', 10, 'ENABLED', false, 0),
    (38, 'platform_excel_task_status', 'RUNNING', '执行中', 20, 'ENABLED', false, 0),
    (39, 'platform_excel_task_status', 'SUCCESS', '成功', 30, 'ENABLED', false, 0),
    (40, 'platform_excel_task_status', 'PARTIAL', '部分成功', 40, 'ENABLED', false, 0),
    (41, 'platform_excel_task_status', 'FAILED', '失败', 50, 'ENABLED', false, 0),
    (42, 'platform_excel_task_status', 'CANCELLED', '已取消', 60, 'ENABLED', false, 0),
    (43, 'platform_alert_severity', 'INFO', '提示', 10, 'ENABLED', false, 0),
    (44, 'platform_alert_severity', 'WARN', '警告', 20, 'ENABLED', false, 0),
    (45, 'platform_alert_severity', 'CRITICAL', '严重', 30, 'ENABLED', false, 0)
ON CONFLICT DO NOTHING;

-- P1 T7 落地 3.4.5 的 6 个内置任务（P1 册 4.5「内置任务（6 条）」）。ID 区间 51–60
-- （与参数 1–10、字典类型 21–24、字典项 31–45 不冲突；雪花 ID 远大于 9999）。
--
-- 两条口径：
--   1. `retry_max`：册面 4.5 写「`retry_max = 0`（重试扫描任务）或 3（其余）」。6 条里**重投扫描**
--      语义的是 `platform.event.retry`（`*/30 * * * * *`，重投 event_delivery，3.4.5 第 4 行）
--      → 取 0；其余 5 条取 3。任务重试扫描器（`platform.job.retry.scan`，3.4.4）**不是** job 行：
--      它由调度器内部按固定 30 秒注册（3.4.7 要求它"不重试自己"，内部任务天然满足）。差异与理由
--      登记在《实现注记（T7）》。
--   2. 6 条全部 `enabled = true`；`handler_code` 与 `job_code` 逐字相同（3.4.5 的前两列）。
--      T7 只注册 `platform.job.log.clean` 一个处理点：其余 5 个能力的表（file/event/alert/notice）
--      在 V5 时点还不存在，按 3.4.2「单个坏任务只跳过自己并记 ERROR，不阻断启动」处理——
--      任务行先种下（`job` 就是注册表），处理点随各能力的票在代码里注册后自动生效。
INSERT INTO eaio_platform.job
    (id, job_code, job_name, handler_code, cron, enabled, timeout_seconds, retry_max, backoff_seconds,
     allow_concurrent, remark, created_by)
VALUES
    (51, 'platform.job.log.clean', '任务运行日志清理', 'platform.job.log.clean', '0 0 3 * * *',
        true, 300, 3, 30, false, '删除超保留天数的 job_run，并把疑似实例宕机的 RUNNING 行置 FAILED（3.4.7）', 0),
    (52, 'platform.file.session.expire', '分片上传会话过期清理', 'platform.file.session.expire', '0 */10 * * * *',
        true, 300, 3, 30, false, '过期分片会话置 EXPIRED 并删分片文件（4.3.6）', 0),
    (53, 'platform.file.orphan.clean', '孤儿文件清理', 'platform.file.orphan.clean', '0 30 3 * * *',
        true, 300, 3, 30, false, '无 file_binding 且超期的 file 软删，再超期物理删（4.3.8）', 0),
    (54, 'platform.event.retry', '事件投递重试扫描', 'platform.event.retry', '*/30 * * * * *',
        true, 300, 0, 30, false, '重投 event_delivery；重投类任务不重试自己，避免重试风暴（3.4.7）', 0),
    (55, 'platform.alert.evaluate', '告警规则评估', 'platform.alert.evaluate', '0 * * * * *',
        true, 300, 3, 30, false, '按 alert_rule 评估指标并触发告警（3.7）', 0),
    (56, 'platform.notice.publish.scan', '定时公告发布扫描', 'platform.notice.publish.scan', '0 * * * * *',
        true, 300, 3, 30, false, '到点的定时公告由 DRAFT 置 PUBLISHED（3.8）', 0)
ON CONFLICT DO NOTHING;

-- P1 T8 追加事件可靠性的 3 个系统参数（P1 册 7.2 的 platform.event.* 三行）。ID 区间 61–70
-- （与参数 1–10、字典类型 21–24、字典项 31–45、任务 51–56 不冲突；雪花 ID 远大于 9999）。
-- `param_group = 'event'`：7.2 的表只有「键/默认值/类型/热更新/说明」五列，没有分组列——分组是
-- 4.3.1 的列（NOT NULL），按既有先例（time/file/excel）取能力名，登记在《实现注记（T8）》。
-- 三个键都 hot_reload：重投扫描每轮都读参数中心，改完下一轮生效（不用重启）。
INSERT INTO eaio_platform.param
    (id, param_key, param_level, owner_id, param_value, value_type, param_group, encrypted, builtin, hot_reload, created_by)
VALUES
    (61, 'platform.event.retry-max', 'SYSTEM', 0, '5', 'INT', 'event', false, true, true, 0),
    (62, 'platform.event.retry-backoff-seconds', 'SYSTEM', 0, '30', 'INT', 'event', false, true, true, 0),
    (63, 'platform.event.delivery-retain-days', 'SYSTEM', 0, '7', 'INT', 'event', false, true, true, 0)
ON CONFLICT DO NOTHING;

