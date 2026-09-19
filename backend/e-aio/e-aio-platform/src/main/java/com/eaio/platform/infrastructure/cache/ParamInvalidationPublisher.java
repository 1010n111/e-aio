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
 * 参数缓存失效的跨实例广播发布方（P1 册 3.1.4"三件事"的第三件：清本机 L1 + 删 L2 + PUBLISH）。
 *
 * <p>通道 {@code eaio:{env}:platform:ch:invalidation}（4.6 键表），载荷 {@code {env, region, key}}（3.1.4
 * 序列图）；{@code key} 为空表示全量失效。
 *
 * <p><b>为什么直接用 {@link StringRedisTemplate}</b>：{@link com.eaio.common.redis.RedisKit} 的冻结方法集
 * （P0 册 4.4）里没有 Pub/Sub 能力，与 {@link ParamL2Cache} 的前缀删除同理——门面缺的能力由基础设施层
 * 直连补齐，**不改 common 契约**，正常读写仍走门面。
 *
 * <p><b>不可用时的口径</b>：广播失败只影响**其他实例**的新鲜度（窗口 = 它们的 L1 TTL 60s，3.1.5 已登记的
 * 代价），本机 L1/L2 照常失效；这里记 WARN，不退化成"假装发成功"。
 */
@Component
public class ParamInvalidationPublisher {

    /** 载荷里的 region 段（3.1.4：{@code region: "param"}）。 */
    static final String REGION_PARAM = "param";

    private static final Logger log = LoggerFactory.getLogger(ParamInvalidationPublisher.class);

    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final String env;

    public ParamInvalidationPublisher(ObjectProvider<StringRedisTemplate> redisTemplates, Environment environment) {
        this.redisTemplates = redisTemplates;
        this.env = CacheEnv.of(environment);
    }

    /** 广播某键失效；{@code key} 为空表示该 region 全量失效。 */
    public void publish(String key) {
        StringRedisTemplate template = redisTemplates.getIfAvailable();
        if (template == null) {
            log.warn("未配置 Redis，参数失效无法跨实例广播（其他实例最长 60s 后由 L1 TTL 兜底）：key={}", key);
            return;
        }
        try {
            template.convertAndSend(channel(), JsonUtils.toJson(new Payload(env, REGION_PARAM, key)));
        } catch (RuntimeException e) {
            log.warn("参数失效广播失败（其他实例最长 60s 后由 L1 TTL 兜底）：key={}，原因：{}", key, e.getMessage());
        }
    }

    /** 广播通道：订阅方必须用同一个，故由发布方唯一构造（避免两处各拼一次键）。 */
    public String channel() {
        return RedisKeys.of(env, "platform", "ch", "invalidation");
    }

    /** 广播载荷：只放标量（与 3.9 事件载荷同款口径）。 */
    public record Payload(String env, String region, String key) {
    }
}
