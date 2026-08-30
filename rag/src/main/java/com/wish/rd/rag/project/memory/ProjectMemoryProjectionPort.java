package com.wish.rd.rag.project.memory;

/**
 * Future projection SPI for already-committed ACTIVE project-memory detail.
 *
 * <p>Implementations must stay off the local head CAS and stage-delivery path. This change
 * ships only a default-off port; OpenViking memory outbox, binding, adapter, and worker are
 * not created here.
 */
public interface ProjectMemoryProjectionPort {

    ProjectMemoryProjectionMode mode();

    default boolean enabled() {
        return mode() != ProjectMemoryProjectionMode.OFF;
    }
}
