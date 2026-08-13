package com.wish.rd.rag.knowledge.projection.model;

/**
 * 存量回填限流：单批上限与未收敛 outbox 上限。
 */
public record InventoryBackfillSettings(int batchSize, int maxInFlight) {

    public static final int DEFAULT_BATCH_SIZE = 20;
    public static final int DEFAULT_MAX_IN_FLIGHT = 200;

    public InventoryBackfillSettings {
        batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxInFlight = maxInFlight <= 0 ? DEFAULT_MAX_IN_FLIGHT : maxInFlight;
    }

    public static InventoryBackfillSettings defaults() {
        return new InventoryBackfillSettings(DEFAULT_BATCH_SIZE, DEFAULT_MAX_IN_FLIGHT);
    }
}
