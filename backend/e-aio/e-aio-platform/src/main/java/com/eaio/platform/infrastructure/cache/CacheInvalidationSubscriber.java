package com.eaio.platform.infrastructure.cache;

import java.nio.charset.StandardCharsets;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.application.cache.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 通用缓存区失效广播的接收方（P1 册 3.6.3、3.6.5 的最后一条测试点）。
 *
 * <p>收到消息只做一件事：**清本机 L1**（L2 是共享的，发布方已经删过）。L1 是本机副本，不广播就清不掉，
 * 而它的 TTL 是 60s——那 60s 里其他实例读到的是旧值。
 *
 * <p><b>与 T3/T4 的订阅方并存</b>：同一条通道上有多个订阅容器，各自按 {@code region} 过滤（参数侧只管
 * {@code param}、字典侧只管 {@code dict}、本类管所有已登记区域的本机 L1）。本类**不**去调
 * {@code ParamResolver}/{@code DictResolver}：那两处的"三件事"已经由各自的订阅方做齐，重复调用只会让
 * 失效语义出现两个版本。
 *
 * <p>回调绝不抛出去（订阅线程一挂，所有实例的失效都失灵）：载荷解析失败、区域未登记、键非法都只记日志。
 */
@Component
public class CacheInvalidationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationSubscriber.class);

    private final CacheManager cache;

    private final String env;

    public CacheInvalidationSubscriber(CacheManager cache, Environment environment) {
        this.cache = cache;
        this.env = CacheEnv.of(environment);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            handle(message);
        } catch (RuntimeException e) {
            log.warn("缓存失效广播处理失败，已忽略本条（订阅回调绝不能抛出去）：{}", e.getMessage());
        }
    }

    private void handle(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        CacheInvalidationPublisher.Payload payload;
        try {
            payload = JsonUtils.fromJson(body, CacheInvalidationPublisher.Payload.class);
        } catch (RuntimeException e) {
            log.warn("缓存失效广播载荷无法解析，忽略本条：{}", e.getMessage());
            return;
        }
        if (payload == null || !env.equals(payload.env())) {
            log.debug("忽略其他环境的失效广播：env={}", payload == null ? null : payload.env());
            return;
        }
        CacheRegion region = CacheRegion.of(payload.region()).orElse(null);
        if (region == null) {
            log.debug("忽略未登记区域的失效广播：region={}", payload.region());
            return;
        }
        if (payload.keyPrefix() != null && !payload.keyPrefix().isBlank()) {
            cache.clearLocalByPrefix(region, payload.keyPrefix());
            return;
        }
        if (payload.key() == null || payload.key().isBlank()) {
            cache.clearLocalByPrefix(region, "");
            return;
        }
        cache.clearLocal(new CacheKey(region, payload.key()));
        log.debug("已按广播清理本机 L1：region={}，key={}", region.code(), payload.key());
    }
}
