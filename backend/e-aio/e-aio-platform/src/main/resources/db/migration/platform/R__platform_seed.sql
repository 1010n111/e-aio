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
