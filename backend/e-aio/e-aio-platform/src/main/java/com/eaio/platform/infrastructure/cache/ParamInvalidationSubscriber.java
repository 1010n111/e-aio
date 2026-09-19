package com.eaio.platform.infrastructure.cache;

import java.nio.charset.StandardCharsets;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.application.param.ParamResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 参数失效广播的接收方（P1 册 3.1.4：其他实例收到广播后清本机缓存）。
 *
 * <p>订阅方走 {@link ParamResolver#invalidate(String)}（清 L1 的该键全部上下文变体 + 删 L2 前缀），
 * 而不是"只清 L1"：发布方的 L2 前缀删除是尽力而为（{@link ParamL2Cache} 里失败只记 WARN），只清 L1
 * 会把脏 L2 留在原地；参数量级 ≤2000 键（7.2），多一次前缀删除的代价可接受。
 *
 * <p>只在真的配了 Redis 时注册（{@code spring.data.redis.host}）：否则订阅容器会持续重连，把
 * "无库无 Redis 的空应用"（镜像默认形态）刷满 WARN。
 */
@Component
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
public class ParamInvalidationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ParamInvalidationSubscriber.class);

    private final ParamResolver resolver;
    private final String env;

    public ParamInvalidationSubscriber(ParamResolver resolver, Environment environment) {
        this.resolver = resolver;
        this.env = CacheEnv.of(environment);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            handle(message);
        } catch (RuntimeException e) {
            log.warn("参数失效广播处理失败，已忽略本条（订阅回调绝不能抛出去：回调挂掉会让所有实例的失效都失灵）：{}",
                    e.getMessage());
        }
    }

    private void handle(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        ParamInvalidationPublisher.Payload payload;
        try {
            payload = JsonUtils.fromJson(body, ParamInvalidationPublisher.Payload.class);
        } catch (RuntimeException e) {
            log.warn("参数失效广播载荷无法解析，忽略本条：{}", e.getMessage());
            return;
        }
        if (payload == null || !env.equals(payload.env())) {
            log.debug("忽略其他环境的失效广播：env={}", payload == null ? null : payload.env());
            return;
        }
        if (!ParamInvalidationPublisher.REGION_PARAM.equals(payload.region())) {
            log.debug("忽略非 param 区的失效广播：region={}", payload.region());
            return;
        }
        if (payload.key() == null || payload.key().isBlank()) {
            resolver.invalidateAll();
        } else {
            resolver.invalidate(payload.key());
        }
    }
}
