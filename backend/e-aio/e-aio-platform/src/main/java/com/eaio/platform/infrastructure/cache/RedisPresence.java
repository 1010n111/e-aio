package com.eaio.platform.infrastructure.cache;

import org.springframework.core.env.Environment;

/**
 * "到底有没有配 Redis"的判定（platform 侧唯一实现）：{@code spring.data.redis.host} 或
 * {@code spring.data.redis.port} 任一存在即视为已配置。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：判定是 OR，而该注解的多属性语义是 AND；写成两个注解、
 * 或在订阅容器与装配层各写一份，迟早分叉——分叉的后果是"幂等走 Redis、跨实例失效却没订阅"这种
 * 静默降级（只在多实例下暴露，本机永远复现不了）。
 *
 * <p>装配层（app 的 {@code WebConfig}）出于模块边界不能复用本类（P1 册 6.3 的
 * {@code platformInternalIsNotReferenced} 禁止外部引用 {@code com.eaio.platform.infrastructure}），
 * 那里的规则与本类逐字相同，并在 P1-3 册《实现注记》里登记为"两份实现、一条规则"。
 */
final class RedisPresence {

    private RedisPresence() {
    }

    static boolean isConfigured(Environment environment) {
        return environment.containsProperty("spring.data.redis.host")
                || environment.containsProperty("spring.data.redis.port");
    }
}
