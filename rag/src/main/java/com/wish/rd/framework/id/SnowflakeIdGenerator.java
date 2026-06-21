package com.wish.rd.framework.id;

import java.time.Clock;
import java.util.function.LongSupplier;

/**
 * Snowflake ID 生成器：为持久化实体生成可排序的 {@code bigint} 主键。
 *
 * <p>供知识库、摄取任务、修复记录等新增持久化实体使用。实现不依赖外部中间件，
 * 默认 workerId/datacenterId 均为 1，生产部署可在 Spring 配置中替换实例。
 */
public final class SnowflakeIdGenerator {

    private static final int WORKER_ID_BITS = 5;
    private static final int DATACENTER_ID_BITS = 5;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;
    private final long datacenterId;
    private final LongSupplier currentTimeMillis;
    private long sequence;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId, LongSupplier currentTimeMillis) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId must be between 0 and " + MAX_DATACENTER_ID);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.currentTimeMillis = currentTimeMillis == null
                ? Clock.systemUTC()::millis
                : currentTimeMillis;
    }

    /**
     * 创建默认生成器，适合单机本地运行。
     *
     * @return 默认 Snowflake ID 生成器
     */
    public static SnowflakeIdGenerator defaultGenerator() {
        return new SnowflakeIdGenerator(1, 1, Clock.systemUTC()::millis);
    }

    /**
     * 生成下一个 {@code bigint} ID。
     *
     * @return 正整数 Snowflake ID
     * @throws IllegalStateException 系统时钟发生回拨时抛出
     */
    public synchronized long nextId() {
        long timestamp = currentTimeMillis.getAsLong();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards by " + (lastTimestamp - timestamp) + "ms");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return (timestamp << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 生成字符串形式 ID，兼容现有 REST/前端的字符串 ID 表示。
     *
     * @return Snowflake ID 字符串
     */
    public String nextIdString() {
        return Long.toString(nextId());
    }

    private long waitUntilNextMillis(long timestamp) {
        long now = currentTimeMillis.getAsLong();
        while (now <= timestamp) {
            Thread.onSpinWait();
            now = currentTimeMillis.getAsLong();
        }
        return now;
    }
}
