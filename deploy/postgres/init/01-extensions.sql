-- 本地编排初始化（仅首次创建数据卷时执行一次；幂等，重复执行无副作用）
-- 与 CI 的 Testcontainers 使用同一镜像，因此扩展可用性两边一致（P0 册 3.8）。

-- pgvector：P0 只建扩展，向量检索在 P1 使用（报表/AI），此处保证环境"零改动"
CREATE EXTENSION IF NOT EXISTS vector;

-- 应用角色建 Schema 的权限：迁移由应用执行（createSchemas=true），每个模块一个 Schema（P0 册 3.6）
-- 生产环境由 DBA 预建 Schema 并授权，不依赖本脚本（数据库权限模型见 3.6）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eaio') THEN
        RAISE NOTICE '角色 eaio 不存在，跳过授权（生产由 DBA 预建）';
    ELSE
        EXECUTE 'GRANT CREATE ON DATABASE ' || quote_ident(current_database()) || ' TO eaio';
    END IF;
END
$$;
