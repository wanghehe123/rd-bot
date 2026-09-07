package com.wish.rd.engine.requirement.query;

/**
 * Read-only snapshot loader for one Coding MEA neighborhood.
 *
 * <p>Production adapters execute inside a single PostgreSQL {@code REPEATABLE_READ}
 * read-only transaction. Implementations must not write.
 */
public interface CodingMeaReadPort {

    /**
     * Loads one consistent snapshot.
     *
     * @param taskId owning task
     * @param codingStageRunId optional Coding stage; blank selects the latest Coding stage
     * @param limit command page size 1–100
     * @param cursor opaque page cursor, or blank
     * @return raw snapshot for {@link CodingMeaQueryEngine}
     */
    CodingMeaSnapshot readSnapshot(String taskId, String codingStageRunId, int limit, String cursor);
}
