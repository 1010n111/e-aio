package com.eaio.platform.infrastructure.cache;

import com.eaio.common.json.JsonUtils;
import com.eaio.common.redis.RedisKeys;
import com.eaio.platform.api.CacheRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 通用缓存区失效的跨实例广播发布方（P1 册 3.6.3 的第三件事：删 L2 + 清本机 L1 + PUBLISH）。
 *
 * <p><b>与参数/字典共用同一条通道</b> {@code eaio:{env}:platform:ch:invalidation}（4.6 只有一条失效
 * 通道）：新能力多加一个 {@code region} 段，而不是多开一条通道——通道数越少，装配与排障的面越小。
 *
 * <p><b>载荷比 T3/T4 多一个 {@code keyPrefix}</b>：3.6.3 的批量失效要广播"前缀"，而单键载荷只能表达
 * 单键。前缀消息里 {@code key} 与 {@code keyPrefix} 同值：T3/T4 的订阅方（{@code ParamInvalidationSubscriber}
 * 等）解析不了新字段就按"单键"处理（{@code JsonUtils} 关掉了未知字段报错），拿到的仍是**这次要清的那个
 * 键前缀文本**——既不会误判成"全量失效"（{@code key} 为空才会），也不会漏清本机 L1。
 *
 * <p>{@link com.eaio.common.redis.RedisKit} 没有 Pub/Sub 能力（P0 册 4.4 冻结），故直连模板；
 * Redis 不可用时记 WARN 不抛：广播失败只影响**其他实例**的新鲜度（窗口 = 它们的 L1 TTL）。
 */
@Component
public class CacheInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    private final ObjectProvider<StringRedisTemplate> redisTemplates;

    private final String env;

    public CacheInvalidationPublisher(ObjectProvider<StringRedisTemplate> redisTemplates, Environment environment) {
        this.redisTemplates = redisTemplates;
        this.env = CacheEnv.of(environment);
    }

    /** 广播单键失效。 */
    public void publishKey(CacheRegion region, String key) {
        send(new Payload(env, region.code(), key, null));
    }

    /** 广播前缀失效；{@code keyPrefix} 为空串表示该区域全量失效。 */
    public void publishPrefix(CacheRegion region, String keyPrefix) {
        send(new Payload(env, region.code(), keyPrefix, keyPrefix));
    }

    /** 广播通道：订阅方必须用同一个，故由发布方唯一构造（与参数/字典侧同一条）。 */
    public String channel() {
        return RedisKeys.of(env, "platform", "ch", "invalidation");
    }

    /** 广播载荷：只放标量（与 3.9 事件载荷同款口径）。 */
    public record Payload(String env, String region, String key, String keyPrefix) {
    }

    private void send(Payload payload) {
        StringRedisTemplate template = redisTemplates.getIfAvailable();
        if (template == null) {
            log.warn("未配置 Redis，缓存失效无法跨实例广播（其他实例最长由 L1 TTL 兜底）：region={}，key={}",
                    payload.region(), payload.key());
            return;
        }
        try {
            template.convertAndSend(channel(), JsonUtils.toJson(payload));
        } catch (RuntimeException e) {
            log.warn("缓存失效广播失败（其他实例最长由 L1 TTL 兜底）：region={}，key={}，原因：{}",
                    payload.region(), payload.key(), e.getMessage());
        }
    }
}
