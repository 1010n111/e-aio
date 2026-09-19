package com.eaio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * e-aio 全应用唯一启动类。
 *
 * <p>扫描范围由所在包决定：Spring Boot 自 {@code com.eaio} 向下扫描，因此 P1 起新增模块只要落在
 * {@code com.eaio.<module>} 包内即自动纳入，<b>不在此处写 @ComponentScan 白名单</b>（P0 册 3.1.3）。
 *
 * <p>本类位于应用壳，应用壳只做装配，不含业务代码。
 */
@SpringBootApplication
public class EaioApplication {

    public static void main(String[] args) {
        SpringApplication.run(EaioApplication.class, args);
    }
}
