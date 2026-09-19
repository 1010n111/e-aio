package com.eaio.common.id;

import java.time.Instant;

/**
 * 雪花 ID 生成器（契约 V1，P0 册 4.2）：64 位、趋势递增、全局唯一，作为全部表主键。
 *
 * <p>位分配：1 位符号（恒 0）+ 41 位毫秒时间戳（相对 {@link #EPOCH}）+ 10 位 workerId + 12 位序列号。
 * 每毫秒最多 4096 个 ID，41 位时间戳可用约 69 年。
 *
 * <p>时钟回拨**直接抛异常**，不等待也不生成重复 ID：回拨期间静默继续会产出与历史 ID 冲突的值，
 * 那是数据损坏，比启动失败严重得多。
 */
public final class IdGenerator {

    /** 起始时间（2026-01-01T00:00:00Z），缩短时间戳位宽占用。 */
    public static final long EPOCH = 1767225600000L;

    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public IdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0–" + MAX_WORKER_ID + " 之间，实际：" + workerId);
        }
        this.workerId = workerId;
    }

    /** 下一个 ID。 */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "时钟回拨，拒绝生成 ID：回拨 " + (lastTimestamp - timestamp) + " ms（last=" + lastTimestamp
                            + ", now=" + timestamp + "）");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << (WORKER_ID_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequence;
    }

    /** 下一个 ID 的字符串形式。 */
    public String nextStr() {
        return Long.toString(nextId());
    }

    /** 反解 ID 的组成，用于排障（时间 + 机器号 + 序号）。 */
    public static Decoded decode(long id) {
        long timestamp = (id >>> (WORKER_ID_BITS + SEQUENCE_BITS)) + EPOCH;
        long worker = (id >>> SEQUENCE_BITS) & MAX_WORKER_ID;
        long seq = id & SEQUENCE_MASK;
        return new Decoded(Instant.ofEpochMilli(timestamp), worker, seq);
    }

    private static long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /** ID 的反解结果。 */
    public record Decoded(Instant generatedAt, long workerId, long sequence) {
    }
}
