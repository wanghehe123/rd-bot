package com.wish.rd.engine.admin.projectmemory;

import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryPurgeExecuteResult;
import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryPurgePreviewResult;
import com.wish.rd.rag.project.memory.ProjectMemoryPurgePort;
import com.wish.rd.rag.project.memory.model.ProjectMemoryPurgeCounts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Project-level physical purge protocol:
 * authorize(PROJECT_MEMORY_PURGE) → preview → confirm token → re-authorize → execute.
 */
@Service
public class ProjectMemoryPurgeService {

    public static final long DEFAULT_CONFIRM_TOKEN_TTL_MILLIS = 300_000L;

    private final Optional<TrustedOperatorPrincipalProvider> principalProvider;
    private final ProjectMemoryMutationAuthorizer authorizer;
    private final ProjectMemoryPurgePort purgePort;
    private final ProjectMemoryPurgeConfirmTokenStore confirmTokenStore;
    private final LongSupplier clock;

    @Autowired
    public ProjectMemoryPurgeService(
            Optional<TrustedOperatorPrincipalProvider> principalProvider,
            ProjectMemoryMutationAuthorizer authorizer,
            ProjectMemoryPurgePort purgePort,
            ProjectMemoryPurgeConfirmTokenStore confirmTokenStore
    ) {
        this(principalProvider, authorizer, purgePort, confirmTokenStore, System::currentTimeMillis);
    }

    public ProjectMemoryPurgeService(
            Optional<TrustedOperatorPrincipalProvider> principalProvider,
            ProjectMemoryMutationAuthorizer authorizer,
            ProjectMemoryPurgePort purgePort,
            ProjectMemoryPurgeConfirmTokenStore confirmTokenStore,
            LongSupplier clock
    ) {
        this.principalProvider = principalProvider == null ? Optional.empty() : principalProvider;
        this.authorizer = authorizer;
        this.purgePort = purgePort;
        this.confirmTokenStore = confirmTokenStore;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public boolean purgeEnabled() {
        return principalProvider.isPresent();
    }

    public ProjectMemoryPurgePreviewResult preview(ProjectMemoryPurgePreviewRequest request) {
        TrustedOperatorPrincipal principal = requirePrincipal();
        String projectId = requireProjectId(request.projectId());
        String reason = requireReason(request.reason());
        String requestId = requireRequestId(request.requestId());
        authorizer.authorize(principal, projectId, ProjectMemoryMutationAction.PURGE_PREVIEW, requestId);

        ProjectMemoryPurgeCounts expectedCounts = purgePort.previewCounts(projectId);
        long expiresAt = clock.getAsLong() + DEFAULT_CONFIRM_TOKEN_TTL_MILLIS;
        ProjectMemoryPurgeConfirmToken confirmToken = confirmTokenStore.issue(
                projectId,
                principal.operatorId(),
                reason,
                expectedCounts.totalRowCount(),
                expiresAt
        );
        return new ProjectMemoryPurgePreviewResult(
                projectId,
                principal.operatorId(),
                reason,
                expectedCounts,
                confirmToken.token(),
                confirmToken.expiresAtEpochMillis(),
                requestId
        );
    }

    public ProjectMemoryPurgeExecuteResult execute(ProjectMemoryPurgeExecuteRequest request) {
        TrustedOperatorPrincipal principal = requirePrincipal();
        String projectId = requireProjectId(request.projectId());
        String reason = requireReason(request.reason());
        String requestId = requireRequestId(request.requestId());
        ProjectMemoryPurgeConfirmToken confirmToken = confirmTokenStore.consume(
                projectId,
                request.confirmToken(),
                principal.operatorId(),
                clock.getAsLong()
        );
        if (!confirmToken.reason().equals(reason)) {
            throw new ProjectMemoryPurgeConfirmTokenInvalidException("confirm token reason mismatch");
        }
        authorizer.authorize(principal, projectId, ProjectMemoryMutationAction.PURGE_EXECUTE, requestId);

        ProjectMemoryPurgeCounts previewCounts = purgePort.previewCounts(projectId);
        if (previewCounts.totalRowCount() != confirmToken.expectedTotalRowCount()) {
            throw new ProjectMemoryPurgeRowCountMismatchException(
                    "purge row count changed since preview: expected "
                            + confirmToken.expectedTotalRowCount()
                            + " actual "
                            + previewCounts.totalRowCount()
            );
        }
        ProjectMemoryPurgeCounts actualCounts = purgePort.executePurge(projectId);
        if (actualCounts.totalRowCount() != confirmToken.expectedTotalRowCount()) {
            throw new ProjectMemoryPurgeRowCountMismatchException(
                    "purge deleted unexpected row count: expected "
                            + confirmToken.expectedTotalRowCount()
                            + " actual "
                            + actualCounts.totalRowCount()
            );
        }
        return new ProjectMemoryPurgeExecuteResult(
                projectId,
                principal.operatorId(),
                reason,
                new ProjectMemoryPurgeCounts(
                        previewCounts.memoryCount(),
                        previewCounts.revisionCount(),
                        previewCounts.sourceCount(),
                        previewCounts.operationCount(),
                        previewCounts.legacyLinkCount()
                ),
                actualCounts,
                requestId
        );
    }

    private TrustedOperatorPrincipal requirePrincipal() {
        if (principalProvider.isEmpty()) {
            throw new ProjectMemoryMutationDisabledException(
                    "project memory purge is disabled until a trusted principal provider is configured"
            );
        }
        return principalProvider.get().current()
                .orElseThrow(() -> new ProjectMemoryMutationDeniedException("trusted operator principal is required"));
    }

    private static String requireProjectId(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        return normalized;
    }

    private static String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return normalized;
    }

    private static String requireRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        return normalized;
    }

    public record ProjectMemoryPurgePreviewRequest(
            String projectId,
            String reason,
            String requestId,
            String requestedActor
    ) {}

    public record ProjectMemoryPurgeExecuteRequest(
            String projectId,
            String reason,
            String confirmToken,
            String requestId,
            String requestedActor
    ) {}
}
