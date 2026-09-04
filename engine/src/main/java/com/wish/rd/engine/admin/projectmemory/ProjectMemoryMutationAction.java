package com.wish.rd.engine.admin.projectmemory;

/** Governance actions audited by {@link ProjectMemoryMutationAuthorizer}. */
public enum ProjectMemoryMutationAction {
    CONFIRM,
    CORRECT,
    INVALIDATE,
    SOFT_DELETE,
    PURGE_PREVIEW,
    PURGE_EXECUTE
}
