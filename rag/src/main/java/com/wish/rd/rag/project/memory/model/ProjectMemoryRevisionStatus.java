package com.wish.rd.rag.project.memory.model;

/** Lifecycle of one immutable memory revision. */
public enum ProjectMemoryRevisionStatus {
    CANDIDATE,
    ACTIVE,
    SUPERSEDED,
    EXPIRED,
    REJECTED,
    QUARANTINED,
    DELETED;

    public boolean retrievable() {
        return this == ACTIVE;
    }
}
