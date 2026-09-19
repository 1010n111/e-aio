package com.eaio.app.redis;

import java.time.Duration;
import java.util.function.Supplier;

import com.eaio.common.json.JsonUtils;
import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisKit} 的装配层实现：底层用 Spring Data 的 {@code StringRedisTemplate}。
 *
 * <p>这一层是"通用能力层零运行时依赖"的代价与收益所在：Redis 客户端类型只出现在装配层，
 * common 只留 JDK 类型签名。P0 的幂等占位原本直接使用模板，现在统一经本类——同一份 SETNX/TTL
 * 语义只实现一次，后续平台的缓存/广播也复用它（P1-2 册 2.7 的口径）。
 *
 * <p>异常映射：客户端抛出的任何运行时异常（连接失败、命令错误）与"结果无法判定"的 {@code null}
 * 都转成 {@link RedisUnavailableException}；值编码失败**不**映射为不可用——那是调用方的数据问题，
 * 不能伪装成基础设施故障。
 */
public class SpringRedisKit implements RedisKit {

    private final StringRedisTemplate redis;
    private final Duration defaultTtl;

    public SpringRedisKit(StringRedisTemplate redis, Duration defaultTtl) {
        this.redis = redis;
        this.defaultTtl = defaultTtl;
    }

    @Override
    public void set(String key, Object value) {
        set(key, value, defaultTtl);
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        String encoded = encode(value);
        if (ttl == null) {
            command(key, () -> redis.opsForValue().set(key, encoded));
            return;
        }
        command(key, () -> redis.opsForValue().set(key, encoded, ttl));
    }

    @Override
    public <T> T get(String key, Class<T> clazz) {
        return decode(command(key, () -> redis.opsForValue().get(key)), clazz);
    }

    @Override
    public void delete(String key) {
        command(key, () -> redis.delete(key));
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return requireDecision(key, "expire", command(key, () -> redis.expire(key, ttl)));
    }

    @Override
    public boolean setIfAbsent(String key, Object value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("setIfAbsent 的 ttl 必须是正数（无 TTL 的占位会永久挡住后续请求）：key=" + key);
        }
        String encoded = encode(value);
        return requireDecision(key, "setIfAbsent", command(key, () -> redis.opsForValue().setIfAbsent(key, encoded, ttl)));
    }

    @Override
    public void push(String queue, Object message) {
        String encoded = encode(message);
        command(queue, () -> redis.opsForList().rightPush(queue, encoded));
    }

    @Override
    public <T> T poll(String queue, Class<T> clazz, Duration timeout) {
        return decode(command(queue, () -> redis.opsForList().leftPop(queue, timeout)), clazz);
    }

    /** String 原样（P0 幂等值逐字不变），其余走 JSON。编码失败由调用方的数据问题负责——
     *  {@link JsonUtils} 抛的异常不会被 {@code command} 包装成"Redis 不可用"，因此编码都在进
     *  {@code command} 之前完成。 */
    private static String encode(Object value) {
        return value instanceof String text ? text : JsonUtils.toJson(value);
    }

    private static <T> T decode(String raw, Class<T> clazz) {
        if (raw == null) {
            return null;
        }
        return clazz == String.class ? clazz.cast(raw) : JsonUtils.fromJson(raw, clazz);
    }

    /** {@code null} 结果 = 无法判定（例如 SETNX 未返回值）：按不可用处理，绝不当成"没拿到"。 */
    private static boolean requireDecision(String key, String operation, Boolean result) {
        if (result == null) {
            throw new RedisUnavailableException("Redis " + operation + " 未返回结果：key=" + key);
        }
        return result;
    }

    private static <T> T command(String key, Supplier<T> command) {
        try {
            return command.get();
        } catch (RuntimeException e) {
            throw wrap(key, e);
        }
    }

    /** 无返回值的命令：复用同一处异常映射，避免两条 try/catch 各写一遍。 */
    private static void command(String key, Runnable command) {
        command(key, () -> {
            command.run();
            return null;
        });
    }

    private static RuntimeException wrap(String key, RuntimeException cause) {
        if (cause instanceof RedisUnavailableException unavailable) {
            return unavailable;
        }
        return new RedisUnavailableException("Redis 命令失败：key=" + key, cause);
    }
}
