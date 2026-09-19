package com.eaio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 空应用启动契约：默认 profile 下不需要数据库、不需要 Redis 即可启动。
 *
 * <p>脚本、迁移与外部依赖都属于后续票；本测试守住"骨架可运行"这一条（P0 验收）。
 *
 * <p>两条显式配置，都是"无依赖启动"这条路径本身要求的：迁移总开关关闭；数据源自动配置排除
 * （classpath 上有 Flyway starter 就会带来 spring-jdbc，Boot 因此无条件建数据源，没有 URL 时启动失败）。
 * 迁移的**可执行**验证由 Testcontainers PostgreSQL 集成测试承担（本机无 Docker 时跳过，权威在 CI）。
 */
@SpringBootTest(properties = {
        "eaio.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class EaioApplicationTest {

    @Test
    @DisplayName("上下文可加载：唯一启动类 + 默认 profile 无需外部依赖")
    void contextLoads() {
        // 上下文加载失败即测试失败
    }
}
