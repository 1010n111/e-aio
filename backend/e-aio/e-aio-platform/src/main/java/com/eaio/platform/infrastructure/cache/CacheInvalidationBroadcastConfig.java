package com.eaio.platform.infrastructure.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 通用缓存区失效广播的订阅容器装配（P1 册 3.6.3 第三件事的接收侧）。
 *
 * <p>与参数/字典各建一个容器是既有的刻意选择（见 {@code DictInvalidationBroadcastConfig}）：一条通道、
 * 多个订阅器，新增能力不必碰已验证的装配代码。代价是同一通道上多一个订阅连接（Pub/Sub 每个订阅连接
 * 各收一份，量级可忽略）。
 *
 * <p>"有没有配 Redis"的判定走 {@link RedisPresence}：没配就不建这个容器——它会持续重连刷 WARN，
 * 而"无库无 Redis 的空应用"是明确支持的形态（镜像默认形态）。
 */
@Configuration(proxyBeanMethods = false)
class CacheInvalidationBroadcastConfig {

    @Bean
    RedisMessageListenerContainer cacheInvalidationContainer(Environment environment,
            RedisConnectionFactory connectionFactory, CacheInvalidationSubscriber subscriber,
            CacheInvalidationPublisher publisher) {
        if (!RedisPresence.isConfigured(environment)) {
            return null;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(publisher.channel()));
        return container;
    }
}
