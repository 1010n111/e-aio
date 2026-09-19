package com.eaio.platform.infrastructure.cache;

import org.springframework.core.env.Environment;

/**
 * Redis 键 {@code {env}} 段的唯一解析点（P1 册 7.2：环境变量 {@code EAIO_ENV} 宽松绑定为 {@code eaio.env}；
 * 键模式 {@code eaio:{env}:…}）。
 *
 * <p>优先级：{@code eaio.env} 配置 &gt; 首个激活 profile（P0 既有口径）&gt; {@code default}。
 * 解析点唯一的理由：缓存键与广播通道必须算出**同一个** env——各算一份就会出现"两个实例互相看不见"
 * 这类只在多实例下暴露的故障（本机永远复现不了）。
 */
final class CacheEnv {

    private CacheEnv() {
    }

    static String of(Environment environment) {
        String configured = environment.getProperty("eaio.env");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : profiles[0];
    }
}
