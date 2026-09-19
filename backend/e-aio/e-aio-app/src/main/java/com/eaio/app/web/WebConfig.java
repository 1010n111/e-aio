package com.eaio.app.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.eaio.app.redis.SpringRedisKit;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.IdempotencyStore;
import com.eaio.common.api.Result;
import com.eaio.common.json.JsonUtils;
import com.eaio.common.redis.RedisKeys;
import com.eaio.common.redis.RedisKit;
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
     * Redis 能力门面（common 的 {@link RedisKit}）：**配了 Redis 才注册**，幂等占位与 platform 的两级缓存
     * L2（`ObjectProvider<RedisKit>`）共用同一个 bean。
     *
     * <p>不加 {@code @ConditionalOnProperty} 的原因：判定是"host **或** port 任一存在"，而该注解的多属性语义
     * 是 AND；同一条件写在两处（这里一处、platform 的订阅容器一处）迟早分叉。返回 {@code null} 时 Spring
     * 不注册该 bean，platform 侧拿到 null 即"L2 关闭"，与"未配 Redis 也能跑完整链路"一致。
     */
    @Bean
    RedisKit redisKit(Environment environment, ObjectProvider<StringRedisTemplate> redisProvider) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (!redisConfigured(environment) || redis == null) {
            return null;
        }
        log.info("Redis 已配置：注册 RedisKit（幂等占位与 L2 缓存共用；env={}）", environmentOf(environment));
        return new SpringRedisKit(redis, defaultTtl(environment));
    }

    /**
     * 占位存储：配了 Redis 就用 Redis；否则退回内存实现（单实例、跨实例不保证幂等）。
     *
     * <p>这样"无 Redis 也能跑完整链路"，而"配了 Redis 却连不上"仍然 fail-closed（10502，不执行业务）。
     */
    @Bean
    IdempotencyStore idempotencyStore(ObjectProvider<RedisKit> redisKitProvider) {
        RedisKit redisKit = redisKitProvider.getIfAvailable();
        if (redisKit != null) {
            log.info("幂等占位使用 Redis（跨实例生效；Redis 不可用时 fail-closed 返回 10502）");
            return new RedisIdempotencyStore(redisKit);
        }
        log.warn("未配置 Redis：幂等占位退回内存实现，**仅单实例有效**，多实例部署必须配置 Redis");
        return new InMemoryIdempotencyStore();
    }

    /** 有没有配 Redis：host 或 port 任一存在即视为已配置（两处判定必须共用这一条，见 {@link #redisKit}）。 */
    private static boolean redisConfigured(Environment environment) {
        return environment.containsProperty("spring.data.redis.host")
                || environment.containsProperty("spring.data.redis.port");
    }

    /**
     * 默认 TTL 取平台缓存的登记属性（P1-2 册 3.4 参数表：{@code eaio.cache.redis.ttl-seconds}，默认 1800 秒）。
     *
     * <p>属性名必须与登记表逐字一致：写错不会报错，只会静默用兜底值——那是最难查的一类"配置没生效"。
     */
    private static Duration defaultTtl(Environment environment) {
        return Duration.ofSeconds(environment.getProperty("eaio.cache.redis.ttl-seconds", Long.class, 1800L));
    }

    /** 环境段取值规则见 {@link RedisKeys#envOf}（唯一实现）：{@code eaio.env} 优先，其次首个激活 profile。 */
    private static String environmentOf(Environment environment) {
        return RedisKeys.envOf(environment.getProperty("eaio.env"), environment.getActiveProfiles());
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
