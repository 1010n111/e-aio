package com.eaio;

import com.eaio.app.config.ModuleFlywayConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;

/**
 * e-aio 全应用唯一启动类。
 *
 * <p>扫描范围由所在包决定：Spring Boot 自 {@code com.eaio} 向下扫描，因此 P1 起新增模块只要落在
 * {@code com.eaio.<module>} 包内即自动纳入，<b>不在此处写 @ComponentScan 白名单</b>（P0 册 3.1.3）。
 *
 * <p>本类位于应用壳，应用壳只做装配，不含业务代码。
 *
 * <p>排除 {@link FlywayAutoConfiguration}：迁移由 {@link ModuleFlywayConfig} 按"每模块一个实例"
 * 装配；不排除的话 Boot 会再按 {@code spring.flyway.*} 建一个全局实例，迁移跑两遍。
 */
@SpringBootApplication(exclude = FlywayAutoConfiguration.class)
public class EaioApplication {

    public static void main(String[] args) {
        SpringApplication.run(EaioApplication.class, args);
    }
}
