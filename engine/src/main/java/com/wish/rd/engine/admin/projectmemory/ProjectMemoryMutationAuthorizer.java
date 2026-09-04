package com.wish.rd.engine.admin.projectmemory;

/** Fail-closed project-scoped authorization for memory governance mutations. */
public interface ProjectMemoryMutationAuthorizer {

    void authorize(
            TrustedOperatorPrincipal principal,
            String projectId,
            ProjectMemoryMutationAction action,
            String requestId
    );
}
