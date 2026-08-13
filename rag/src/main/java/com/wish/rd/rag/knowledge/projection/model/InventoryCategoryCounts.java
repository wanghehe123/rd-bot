package com.wish.rd.rag.knowledge.projection.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 十类存量计数。漂移不在此列，因此 {@link #sum()} 必须能与文档总数对齐。
 */
public record InventoryCategoryCounts(
        long tombstone,
        long superseded,
        long duplicateUnresolved,
        long excludedBaseInactive,
        long excludedLocalOnly,
        long excludedEmpty,
        long failed,
        long inSync,
        long projecting,
        long pendingBackfill
) {

    public InventoryCategoryCounts {
        tombstone = Math.max(0L, tombstone);
        superseded = Math.max(0L, superseded);
        duplicateUnresolved = Math.max(0L, duplicateUnresolved);
        excludedBaseInactive = Math.max(0L, excludedBaseInactive);
        excludedLocalOnly = Math.max(0L, excludedLocalOnly);
        excludedEmpty = Math.max(0L, excludedEmpty);
        failed = Math.max(0L, failed);
        inSync = Math.max(0L, inSync);
        projecting = Math.max(0L, projecting);
        pendingBackfill = Math.max(0L, pendingBackfill);
    }

    public static InventoryCategoryCounts from(Map<InventoryCategory, Long> counts) {
        Map<InventoryCategory, Long> safe = counts == null ? Map.of() : counts;
        return new InventoryCategoryCounts(
                value(safe, InventoryCategory.TOMBSTONE),
                value(safe, InventoryCategory.SUPERSEDED),
                value(safe, InventoryCategory.DUPLICATE_UNRESOLVED),
                value(safe, InventoryCategory.EXCLUDED_BASE_INACTIVE),
                value(safe, InventoryCategory.EXCLUDED_LOCAL_ONLY),
                value(safe, InventoryCategory.EXCLUDED_EMPTY),
                value(safe, InventoryCategory.FAILED),
                value(safe, InventoryCategory.IN_SYNC),
                value(safe, InventoryCategory.PROJECTING),
                value(safe, InventoryCategory.PENDING_BACKFILL)
        );
    }

    public long sum() {
        return tombstone
                + superseded
                + duplicateUnresolved
                + excludedBaseInactive
                + excludedLocalOnly
                + excludedEmpty
                + failed
                + inSync
                + projecting
                + pendingBackfill;
    }

    public long count(InventoryCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        return switch (category) {
            case TOMBSTONE -> tombstone;
            case SUPERSEDED -> superseded;
            case DUPLICATE_UNRESOLVED -> duplicateUnresolved;
            case EXCLUDED_BASE_INACTIVE -> excludedBaseInactive;
            case EXCLUDED_LOCAL_ONLY -> excludedLocalOnly;
            case EXCLUDED_EMPTY -> excludedEmpty;
            case FAILED -> failed;
            case IN_SYNC -> inSync;
            case PROJECTING -> projecting;
            case PENDING_BACKFILL -> pendingBackfill;
        };
    }

    public Map<InventoryCategory, Long> asMap() {
        EnumMap<InventoryCategory, Long> map = new EnumMap<>(InventoryCategory.class);
        for (InventoryCategory category : InventoryCategory.values()) {
            map.put(category, count(category));
        }
        return Map.copyOf(map);
    }

    private static long value(Map<InventoryCategory, Long> counts, InventoryCategory category) {
        Long value = counts.get(category);
        return value == null ? 0L : Math.max(0L, value);
    }
}
