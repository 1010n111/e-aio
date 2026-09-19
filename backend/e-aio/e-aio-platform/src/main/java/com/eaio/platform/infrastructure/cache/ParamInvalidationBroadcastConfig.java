package com.eaio.platform.infrastructure.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 失效广播的订阅容器装配（P1 册 3.1.4 第三件事的接收侧）。
 *
 * <p>"有没有配 Redis"的判定走 {@link RedisPresence}（host 或 port 任一存在）：没配就不建这个容器——
 * 它会持续重连刷 WARN，而"无库无 Redis 的空应用"是明确支持的形态（镜像默认形态，见 Dockerfile）。
 */
@Configuration(proxyBeanMethods = false)
class ParamInvalidationBroadcastConfig {

    @Bean
    RedisMessageListenerContainer paramInvalidationContainer(Environment environment,
            RedisConnectionFactory connectionFactory, ParamInvalidationSubscriber subscriber,
            ParamInvalidationPublisher publisher) {
        if (!RedisPresence.isConfigured(environment)) {
            return null;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(publisher.channel()));
        return container;
    }
}
