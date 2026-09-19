package com.eaio.platform.infrastructure.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 失效广播的订阅容器装配（P1 册 3.1.4 第三件事的接收侧）。
 *
 * <p>条件与装配层判"有没有配 Redis"同款（{@code spring.data.redis.host}，见 {@code WebConfig} 的幂等存储选择）：
 * 没配 Redis 时不建这个容器——它会持续重连刷 WARN，而"无库无 Redis 的空应用"是明确支持的形态
 * （镜像默认形态，见 Dockerfile）。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
class ParamInvalidationBroadcastConfig {

    @Bean
    RedisMessageListenerContainer paramInvalidationContainer(RedisConnectionFactory connectionFactory,
            ParamInvalidationSubscriber subscriber, ParamInvalidationPublisher publisher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(publisher.channel()));
        return container;
    }
}
