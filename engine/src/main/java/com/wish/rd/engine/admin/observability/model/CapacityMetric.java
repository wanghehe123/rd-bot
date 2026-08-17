package com.wish.rd.engine.admin.observability.model;

/**
 * Point-in-time resource capacity.
 *
 * @param resource allowlisted resource
 * @param inUse current use
 * @param capacity configured cap
 * @param saturated true when inUse &gt;= capacity and capacity &gt; 0
 */
public record CapacityMetric(String resource, long inUse, long capacity, boolean saturated) {

    public CapacityMetric {
        resource = resource == null || resource.isBlank() ? "GENERIC" : resource.strip();
        inUse = Math.max(0L, inUse);
        capacity = Math.max(0L, capacity);
        saturated = capacity > 0L && inUse >= capacity;
    }
}
