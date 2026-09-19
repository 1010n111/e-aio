package com.eaio.app.config;

import com.eaio.common.id.IdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 雪花 ID 生成器的装配（P0 册 4.2 契约，P1 首个消费者是参数中心）。
 *
 * <p>为什么在应用壳装配：workerId 是**部署参数**（同一时刻多实例必须互不相同，否则会生成重复 ID——
 * 那是数据损坏级的问题），属于"运行环境"而不是某个模块的知识。模块只注入 {@code IdGenerator} 用。
 *
 * <p>取值 {@code eaio.id.worker-id}（默认 1，允许 0–1023）。多实例部署必须显式配置：容器里建议用
 * Pod 序号或 StatefulSet 序号注入。
 */
@Configuration(proxyBeanMethods = false)
public class IdGeneratorConfig {

    @Bean
    IdGenerator idGenerator(Environment environment) {
        long workerId = environment.getProperty("eaio.id.worker-id", Long.class, 1L);
        return new IdGenerator(workerId);
    }
}
