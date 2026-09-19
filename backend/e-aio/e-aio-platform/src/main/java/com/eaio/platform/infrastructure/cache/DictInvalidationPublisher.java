package com.eaio.platform.infrastructure.cache;

import com.eaio.common.json.JsonUtils;
import com.eaio.common.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 字典缓存失效的跨实例广播发布方（P1 册 3.2.3"本机 + L2 + 广播"的第三件）。
 *
 * <p>与参数共用同一条通道 {@code eaio:{env}:platform:ch:invalidation}（4.6 只有一条失效通道），
 * 靠载荷里的 {@code region} 段区分（{@code param} / {@code dict}）——新增一个能力就多一个 region，
 * 而不是多一条通道：通道数越少，装配与排障的面越小。
 *
 * <p>这里是 {@code ParamInvalidationPublisher} 的同款实现而不是复用它：那个类的 {@code region} 是
 * 编译期常量 {@code "param"}，且参数中心的既有权衡（如"refresh 不广播"）与字典未必相同。
 * 复用它就得先把它改成通用的，而那是改动已验证代码；两个小类各管一个 region 更便宜。
 *
 * <p>Redis 不可用时记 WARN 不抛：广播失败只影响**其他实例**的新鲜度（窗口 = 它们的 L1 TTL 60s），
 * 本机 L1/L2 照常失效。
 */
@Component
public class DictInvalidationPublisher {

    /** 载荷里的 region 段（与订阅方共用，改一处即两处同步）。 */
    static final String REGION_DICT = "dict";

    private static final Logger log = LoggerFactory.getLogger(DictInvalidationPublisher.class);

    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final String env;

    public DictInvalidationPublisher(ObjectProvider<StringRedisTemplate> redisTemplates, Environment environment) {
        this.redisTemplates = redisTemplates;
        this.env = CacheEnv.of(environment);
    }

    /** 广播某类型的失效；{@code typeCode} 为空表示字典区全量失效。 */
    public void publish(String typeCode) {
        StringRedisTemplate template = redisTemplates.getIfAvailable();
        if (template == null) {
            log.warn("未配置 Redis，字典失效无法跨实例广播（其他实例最长 60s 后由 L1 TTL 兜底）：typeCode={}", typeCode);
            return;
        }
        try {
            template.convertAndSend(channel(), JsonUtils.toJson(new Payload(env, REGION_DICT, typeCode)));
        } catch (RuntimeException e) {
            log.warn("字典失效广播失败（其他实例最长 60s 后由 L1 TTL 兜底）：typeCode={}，原因：{}",
                    typeCode, e.getMessage());
        }
    }

    /** 广播通道：订阅方必须用同一个，故由发布方唯一构造。 */
    public String channel() {
        return RedisKeys.of(env, "platform", "ch", "invalidation");
    }

    /** 广播载荷：只放标量（与 3.9 事件载荷同款口径）。 */
    public record Payload(String env, String region, String key) {
    }
}
