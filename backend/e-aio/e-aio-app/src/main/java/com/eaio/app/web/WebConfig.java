package com.eaio.app.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.eaio.app.redis.SpringRedisKit;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.IdempotencyStore;
import com.eaio.common.api.Result;
import com.eaio.common.json.JsonUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 入站链路装配（P0 册 3.3/3.4/3.2.5）：traceId 最先，幂等次之。
 *
 * <p>显式用 {@link FilterRegistrationBean} 排顺序，不用 {@code @Component}+{@code @Order}：
 * 过滤器顺序是契约的一部分（traceId 必须最先建立日志上下文），显式装配才看得见、可回归。
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, Environment environment) {
        IdempotencyFilter filter = new IdempotencyFilter(store, environmentOf(environment), WebConfig::writeEnvelope);
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /**
     * 占位存储：配置了 Redis 就用 Redis；否则退回内存实现（单实例、跨实例不保证幂等）。
     *
     * <p>这样"无 Redis 也能跑完整链路"，而"配了 Redis 却连不上"仍然 fail-closed（10502，不执行业务）。
     */
    @Bean
    IdempotencyStore idempotencyStore(Environment environment, ObjectProvider<StringRedisTemplate> redisProvider) {
        boolean redisConfigured = environment.containsProperty("spring.data.redis.host")
                || environment.containsProperty("spring.data.redis.port");
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redisConfigured && redis != null) {
            log.info("幂等占位使用 Redis（跨实例生效；Redis 不可用时 fail-closed 返回 10502）");
            return new RedisIdempotencyStore(new SpringRedisKit(redis, defaultTtl(environment)));
        }
        log.warn("未配置 Redis：幂等占位退回内存实现，**仅单实例有效**，多实例部署必须配置 Redis");
        return new InMemoryIdempotencyStore();
    }

    /**
     * 默认 TTL 取平台缓存的登记属性（P1-2 册 3.4 参数表：{@code eaio.cache.redis.ttl-seconds}，默认 1800 秒）。
     *
     * <p>属性名必须与登记表逐字一致：写错不会报错，只会静默用兜底值——那是最难查的一类"配置没生效"。
     */
    private static Duration defaultTtl(Environment environment) {
        return Duration.ofSeconds(environment.getProperty("eaio.cache.redis.ttl-seconds", Long.class, 1800L));
    }

    private static String environmentOf(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : profiles[0];
    }

    /** 过滤器层直接写统一返回体：这里不能靠异常处理器（异常在处理链之外抛出）。 */
    static void writeEnvelope(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> body = Result.fail(errorCode);
        body.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        response.getOutputStream().write(JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8));
    }
}
