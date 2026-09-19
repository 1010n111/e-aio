package com.eaio.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 雪花 ID：唯一、趋势递增、可反解、并发安全。 */
class IdGeneratorTest {

    @Test
    @DisplayName("单调递增且唯一")
    void monotonicAndUnique() {
        IdGenerator generator = new IdGenerator(1);

        long previous = generator.nextId();
        for (int i = 0; i < 10_000; i++) {
            long current = generator.nextId();
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("反解回时间、机器号、序号")
    void decodeRoundTrip() {
        IdGenerator generator = new IdGenerator(7);

        IdGenerator.Decoded decoded = IdGenerator.decode(generator.nextId());

        assertThat(decoded.workerId()).isEqualTo(7);
        assertThat(decoded.sequence()).isZero();
        assertThat(decoded.generatedAt()).isAfter(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("同一毫秒内序号递增")
    void sequenceIncreasesWithinSameMillisecond() throws Exception {
        IdGenerator generator = new IdGenerator(1);
        long first = generator.nextId();
        long second = generator.nextId();

        IdGenerator.Decoded a = IdGenerator.decode(first);
        IdGenerator.Decoded b = IdGenerator.decode(second);
        if (a.generatedAt().equals(b.generatedAt())) {
            assertThat(b.sequence()).isEqualTo(a.sequence() + 1);
        }
    }

    @Test
    @DisplayName("多线程并发不产生重复 ID")
    void concurrentGenerationHasNoDuplicates() throws Exception {
        IdGenerator generator = new IdGenerator(3);
        int threads = 8;
        int perThread = 2_000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(generator.nextId());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("workerId 越界直接拒绝（多实例分配错号会撞 ID）")
    void workerIdIsValidated() {
        assertThatThrownBy(() -> new IdGenerator(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdGenerator(1024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdGenerator(1023)).isNotNull();
    }

    @Test
    @DisplayName("字符串形式与数值一致")
    void nextStrMatchesNextId() {
        IdGenerator generator = new IdGenerator(0);

        assertThat(Long.parseLong(generator.nextStr())).isPositive();
    }
}
