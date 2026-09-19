package com.eaio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 空应用启动契约：默认 profile 下不需要数据库、不需要 Redis 即可启动。
 *
 * <p>脚本、迁移与外部依赖都属于后续票；本测试守住"骨架可运行"这一条（P0 验收）。
 *
 * <p>不设任何额外属性：走的就是**生产配置本身**（基础配置不带连接信息、排除数据源自动配置，
 * 迁移因此不装配）。加属性等于换个配置自证，测不到真实路径。
 * 迁移的**可执行**验证由 Testcontainers PostgreSQL 集成测试承担（本机无 Docker 时跳过，权威在 CI）。
 */
@SpringBootTest
@ActiveProfiles("test")
class EaioApplicationTest {

    @Test
    @DisplayName("上下文可加载：唯一启动类 + 默认 profile 无需外部依赖")
    void contextLoads() {
        // 上下文加载失败即测试失败
    }
}

