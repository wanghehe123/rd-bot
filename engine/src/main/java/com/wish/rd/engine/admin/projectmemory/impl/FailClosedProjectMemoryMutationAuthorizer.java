package com.wish.rd.engine.admin.projectmemory.impl;

import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAction;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAuthorizer;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationCapability;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationDeniedException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryGovernanceAuditSink;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipal;

/** Fail-closed authorizer requiring {@link ProjectMemoryMutationCapability#PROJECT_MEMORY_GOVERN}. */
public final class FailClosedProjectMemoryMutationAuthorizer implements ProjectMemoryMutationAuthorizer {

    private final ProjectMemoryGovernanceAuditSink auditSink;

    public FailClosedProjectMemoryMutationAuthorizer(ProjectMemoryGovernanceAuditSink auditSink) {
        this.auditSink = auditSink;
    }

    @Override
    public void authorize(
            TrustedOperatorPrincipal principal,
            String projectId,
            ProjectMemoryMutationAction action,
            String requestId
    ) {
        if (principal == null) {
            throw new ProjectMemoryMutationDeniedException("trusted operator principal is required");
        }
        ProjectMemoryMutationCapability requiredCapability = requiredCapability(action);
        if (!principal.hasCapability(requiredCapability)) {
            auditSink.recordDenied(
                    principal, projectId, action, requestId, "missing " + requiredCapability.name());
            throw new ProjectMemoryMutationDeniedException("missing " + requiredCapability.name() + " capability");
        }
        if (!principal.canGovernProject(projectId)) {
            auditSink.recordDenied(principal, projectId, action, requestId, "project scope denied");
            throw new ProjectMemoryMutationDeniedException("operator cannot govern project: " + projectId);
        }
        auditSink.recordAllowed(principal, projectId, action, requestId, "authorized");
    }

    private static ProjectMemoryMutationCapability requiredCapability(ProjectMemoryMutationAction action) {
        return switch (action) {
            case PURGE_PREVIEW, PURGE_EXECUTE -> ProjectMemoryMutationCapability.PROJECT_MEMORY_PURGE;
            case CONFIRM, CORRECT, INVALIDATE, SOFT_DELETE -> ProjectMemoryMutationCapability.PROJECT_MEMORY_GOVERN;
        };
    }
}
