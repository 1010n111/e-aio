package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * "有没有配 Redis"的判定（评审 F4）。
 *
 * <p>锁的是 **OR 语义**：{@code @ConditionalOnProperty} 的多属性是 AND，写错或写两处会让"只配了 port"的部署
 * 出现"幂等走 Redis、跨实例失效却没订阅"的静默降级——这类故障只在多实例下暴露，必须在单测里钉住规则本身。
 */
class RedisPresenceTest {

    @Test
    @DisplayName("host 或 port 任一存在即视为已配 Redis；都没配则不建订阅容器")
    void configuredWhenHostOrPortPresent() {
        assertThat(RedisPresence.isConfigured(new MockEnvironment().withProperty("spring.data.redis.host", "h")))
                .as("只配 host").isTrue();
        assertThat(RedisPresence.isConfigured(new MockEnvironment().withProperty("spring.data.redis.port", "6379")))
                .as("只配 port").isTrue();
        assertThat(RedisPresence.isConfigured(new MockEnvironment())).as("都没配").isFalse();
    }
}
