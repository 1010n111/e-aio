package com.eaio.platform.infrastructure.cache;

import com.eaio.common.redis.RedisKeys;
import org.springframework.core.env.Environment;

/**
 * Redis 键 {@code {env}} 段的解析入口（platform 侧，P1 册 7.2、P1-2 册 3.8）。
 *
 * <p>**取值规则本身**在 common 的 {@link RedisKeys#envOf}（唯一实现）：{@code eaio.env}
 * （环境变量 {@code EAIO_ENV} 宽松绑定）优先 &gt; 首个激活 profile &gt; {@code default}。
 * 这里只负责从 Spring 配置取值——规则与装配层（{@code WebConfig} 的幂等键）共用同一份实现：
 * 缓存键与广播通道必须算出**同一个** env，两处各写一份就会出现"两个实例互相看不见"这类
 * 只在多实例下暴露、本机永远复现不了的故障。
 */
final class CacheEnv {

    private CacheEnv() {
    }

    static String of(Environment environment) {
        return RedisKeys.envOf(environment.getProperty("eaio.env"), environment.getActiveProfiles());
    }
}
