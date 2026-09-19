package com.eaio.platform.infrastructure.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 字典失效广播的订阅容器装配（P1 册 3.2.3 第三件事的接收侧）。
 *
 * <p>与参数各建一个容器是刻意的：失效通道是**一条**（4.6），但订阅器是两个——把两个监听器塞进
 * 一个容器就得改动 T4 已验证的装配代码，而"每个能力自带订阅容器"让新增能力不必碰既有装配。
 * 代价是同一通道上有两个订阅连接（Redis Pub/Sub 每个订阅连接各收一份，量级可忽略）。
 *
 * <p>"有没有配 Redis"的判定走 {@link RedisPresence}（host 或 port 任一存在，与参数侧同一个实现）：
 * 没配就不建这个容器——它会持续重连刷 WARN，而"无库无 Redis 的空应用"是明确支持的形态（镜像默认形态）。
 */
@Configuration(proxyBeanMethods = false)
class DictInvalidationBroadcastConfig {

    @Bean
    RedisMessageListenerContainer dictInvalidationContainer(Environment environment,
            RedisConnectionFactory connectionFactory, DictInvalidationSubscriber subscriber,
            DictInvalidationPublisher publisher) {
        if (!RedisPresence.isConfigured(environment)) {
            return null;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(publisher.channel()));
        return container;
    }
}
