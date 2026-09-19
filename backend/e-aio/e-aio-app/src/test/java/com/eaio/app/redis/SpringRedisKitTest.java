package com.eaio.app.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;

import com.eaio.common.exception.JsonException;
import com.eaio.common.redis.RedisUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 装配层的 RedisKit 实现：值编码、默认 TTL、以及"不可用"与"没数据"的区分。
 *
 * <p>这里用模板桩测映射逻辑；真实 Redis 的 SETNX/TTL/队列行为由 CI 的 Testcontainers Redis 覆盖
 * （本机无 Docker）。
 */
class SpringRedisKitTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private ValueOperations<String, String> values;
    private ListOperations<String, String> lists;
    private StringRedisTemplate redis;
    private SpringRedisKit kit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        lists = mock(ListOperations.class);
        given(redis.opsForValue()).willReturn(values);
        given(redis.opsForList()).willReturn(lists);
        kit = new SpringRedisKit(redis, DEFAULT_TTL);
    }

    @Test
    @DisplayName("String 值原样写入：P0 幂等占位的 PROCESSING/DONE 必须逐字不变")
    void stringWrittenRaw() {
        kit.set("k", "PROCESSING", TTL);

        verify(values).set("k", "PROCESSING", TTL);
    }

    @Test
    @DisplayName("非 String 值走 JSON：写入与读回成对")
    void objectRoundTripAsJson() {
        kit.set("k", Map.of("a", "1"), TTL);
        verify(values).set("k", "{\"a\":\"1\"}", TTL);

        given(values.get("k")).willReturn("{\"a\":\"1\"}");
        assertThat(kit.get("k", Map.class)).containsEntry("a", "1");
    }

    @Test
    @DisplayName("无 TTL 重载用装配方给的默认 TTL（不是永不过期）")
    void defaultTtlApplied() {
        kit.set("k", "v");

        verify(values).set("k", "v", DEFAULT_TTL);
    }

    @Test
    @DisplayName("键不存在返回 null（正常业务分支），与不可用区分")
    void missingKeyIsNull() {
        given(values.get("k")).willReturn(null);

        assertThat(kit.get("k", String.class)).isNull();
    }

    @Test
    @DisplayName("setIfAbsent：false 表示已被占用；未返回值按不可用处理（绝不当成没抢到）")
    void setIfAbsentDecision() {
        given(values.setIfAbsent("k", "v", TTL)).willReturn(false);
        assertThat(kit.setIfAbsent("k", "v", TTL)).isFalse();

        given(values.setIfAbsent("k2", "v", TTL)).willReturn(null);
        assertThatThrownBy(() -> kit.setIfAbsent("k2", "v", TTL)).isInstanceOf(RedisUnavailableException.class);
    }

    @Test
    @DisplayName("setIfAbsent 的 TTL 必填且为正：无 TTL 的占位在持有者崩溃后永久挡路")
    void setIfAbsentRequiresPositiveTtl() {
        assertThatThrownBy(() -> kit.setIfAbsent("k", "v", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kit.setIfAbsent("k", "v", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("值编码失败不算 Redis 不可用：编码在进命令包装之前完成，抛的是 JsonException")
    void encodeFailureIsNotRedisUnavailable() {
        Object cyclic = new Object[1];
        ((Object[]) cyclic)[0] = cyclic;

        assertThatThrownBy(() -> kit.set("k", cyclic, TTL))
                .as("数据问题不能伪装成基础设施故障，否则排障会被带偏")
                .isInstanceOf(JsonException.class);
    }

    @Test
    @DisplayName("客户端异常映射为 RedisUnavailableException，且保留原因便于排障")
    void connectionFailureMapped() {
        RedisConnectionFailureException cause = new RedisConnectionFailureException("down");
        given(values.get("k")).willThrow(cause);

        assertThatThrownBy(() -> kit.get("k", String.class))
                .isInstanceOf(RedisUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    @DisplayName("队列：右进左出；超时无消息返回 null")
    void queuePushAndPoll() {
        kit.push("q", Map.of("a", "1"));
        verify(lists).rightPush("q", "{\"a\":\"1\"}");

        given(lists.leftPop("q", TTL)).willReturn(null);
        assertThat(kit.poll("q", String.class, TTL)).isNull();
    }

    @Test
    @DisplayName("delete 转发到模板；expire 未返回值按不可用处理")
    void deleteAndExpire() {
        kit.delete("k");
        verify(redis).delete("k");

        given(redis.expire("k", TTL)).willReturn(true);
        assertThat(kit.expire("k", TTL)).isTrue();

        given(redis.expire("k2", TTL)).willReturn(null);
        assertThatThrownBy(() -> kit.expire("k2", TTL)).isInstanceOf(RedisUnavailableException.class);
    }
}
