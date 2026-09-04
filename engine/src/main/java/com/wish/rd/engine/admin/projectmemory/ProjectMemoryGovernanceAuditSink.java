package com.wish.rd.engine.admin.projectmemory;

/** Records allow/deny decisions for governance mutations. */
public interface ProjectMemoryGovernanceAuditSink {

    void recordAllowed(
            TrustedOperatorPrincipal principal,
            String projectId,
            ProjectMemoryMutationAction action,
            String requestId,
            String reason
    );

    void recordDenied(
            TrustedOperatorPrincipal principal,
            String projectId,
            ProjectMemoryMutationAction action,
            String requestId,
            String reason
    );
}
