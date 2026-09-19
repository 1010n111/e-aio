package com.eaio.it;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 集成测试基类（P0 册 3.8 阶段 5）。
 *
 * <p>**镜像与本地编排一致**（`docker-compose.yml` 用同一个 `pgvector/pgvector:pg17` 与 `redis:7-alpine`），
 * 避免"本地编排能跑、CI 跑不了"这类只会在上线前才发现的差异。
 *
 * <p>`disabledWithoutDocker = true`：无 Docker 的机器上**跳过而不是失败**——此时本地验证的权威性
 * 低于 CI（build-and-test.md 写明这条降级路径），CI 上必须真实执行（票面要求）。
 *
 * <p>请求路径带 `/api` 前缀：那是 `server.servlet.context-path`，属对外契约的一部分
 * （P0 册 3.9），测试里覆盖不掉也不该覆盖（见 application-it.yml 的实测说明）。
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
public abstract class IntegrationTestBase {

    /** PostgreSQL 17 + pgvector：与 compose 同镜像（迁移会建 Schema 与历史表）。 */
    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"))
                    .withDatabaseName("eaio")
                    .withUsername("eaio")
                    .withPassword("eaio");

    /** Redis 7：幂等占位（P0 册 3.2.5）。用通用容器，避免额外引入 vendor 模块。 */
    @Container
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        // 数据源：显式给三要素（不依赖 Boot 的容器连接细节发现机制，版本升级不影响这里）
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Redis：让装配处认为"配置了 Redis"，从而启用 RedisIdempotencyStore（而非内存实现）
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
