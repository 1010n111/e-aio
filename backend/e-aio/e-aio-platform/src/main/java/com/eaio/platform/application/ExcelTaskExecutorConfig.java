package com.eaio.platform.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bounded queue makes overload a business error instead of silently dropping work. */
@Configuration
public class ExcelTaskExecutorConfig {

    @Bean(name = "excelTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService excelTaskExecutor() {
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
