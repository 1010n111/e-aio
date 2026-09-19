package com.eaio.platform.infrastructure.cache;

import java.nio.charset.StandardCharsets;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.application.dict.DictResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 字典失效广播的接收方（与参数共用通道，按载荷的 {@code region = "dict"} 过滤）。
 *
 * <p>订阅方走 {@link DictResolver#invalidate(String)}（本机 L1 + L2），而不是"只清 L1"：发布方的
 * L2 删除是尽力而为，只清 L1 会把脏 L2 留在原地。
 *
 * <p>本类是"消息怎么处理"，"要不要订阅"由 {@link DictInvalidationBroadcastConfig} 决定（未配 Redis
 * 时不建容器，本 bean 于是只是闲置，不会发起任何连接）。
 */
@Component
public class DictInvalidationSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DictInvalidationSubscriber.class);

    private final DictResolver resolver;
    private final String env;

    public DictInvalidationSubscriber(DictResolver resolver, Environment environment) {
        this.resolver = resolver;
        this.env = CacheEnv.of(environment);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            handle(message);
        } catch (RuntimeException e) {
            log.warn("字典失效广播处理失败，已忽略本条（订阅回调绝不能抛出去：回调挂掉会让所有实例的失效都失灵）：{}",
                    e.getMessage());
        }
    }

    private void handle(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        DictInvalidationPublisher.Payload payload;
        try {
            payload = JsonUtils.fromJson(body, DictInvalidationPublisher.Payload.class);
        } catch (RuntimeException e) {
            log.warn("字典失效广播载荷无法解析，忽略本条：{}", e.getMessage());
            return;
        }
        if (payload == null || !env.equals(payload.env())) {
            log.debug("忽略其他环境的失效广播：env={}", payload == null ? null : payload.env());
            return;
        }
        if (!DictInvalidationPublisher.REGION_DICT.equals(payload.region())) {
            return;
        }
        resolver.invalidate(payload.key());
    }
}
