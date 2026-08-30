package com.wish.rd.engine.admin.projectmemory;

import com.wish.rd.engine.admin.projectmemory.model.ProjectMemoryAdminMutationResult;
import com.wish.rd.rag.project.memory.ProjectMemoryGovernancePort;
import com.wish.rd.rag.project.memory.model.ProjectMemoryGovernanceResult;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Governance mutation orchestration; disabled when no trusted principal provider is configured. */
@Service
public final class ProjectMemoryAdminMutationService {

    private final Optional<TrustedOperatorPrincipalProvider> principalProvider;
    private final ProjectMemoryMutationAuthorizer authorizer;
    private final ProjectMemoryGovernancePort governancePort;

    public ProjectMemoryAdminMutationService(
            Optional<TrustedOperatorPrincipalProvider> principalProvider,
            ProjectMemoryMutationAuthorizer authorizer,
            ProjectMemoryGovernancePort governancePort
    ) {
        this.principalProvider = principalProvider == null ? Optional.empty() : principalProvider;
        this.authorizer = authorizer;
        this.governancePort = governancePort;
    }

    public boolean mutationsEnabled() {
        return principalProvider.isPresent();
    }

    public ProjectMemoryAdminMutationResult confirm(ProjectMemoryConfirmRequest request) {
        TrustedOperatorPrincipal principal = requirePrincipal();
        authorizer.authorize(principal, request.projectId(), ProjectMemoryMutationAction.CONFIRM, request.requestId());
        ProjectMemoryGovernanceResult result = governancePort.confirm(new ProjectMemoryGovernancePort.ProjectMemoryConfirmCommand(
                request.projectId(),
                request.memoryId(),
                request.revisionId(),
                request.expectedMemoryRowVersion(),
                request.expectedRevisionRowVersion()
        ));
        return toMutationResult(result, principal.operatorId(), request.requestId());
    }

    public ProjectMemoryAdminMutationResult correct(ProjectMemoryCorrectRequest request) {
        TrustedOperatorPrincipal principal = requirePrincipal();
        authorizer.authorize(principal, request.projectId(), ProjectMemoryMutationAction.CORRECT, request.requestId());
        ProjectMemoryGovernanceResult result = governancePort.correct(new ProjectMemoryGovernancePort.ProjectMemoryCorrectCommand(
                request.projectId(),
                request.memoryId(),
                request.revisionId(),
                request.expectedMemoryRowVersion(),
                request.expectedRevisionRowVersion(),
                request.correctedTitle(),
                request.correctedSummary(),
                request.correctedContentJson(),
                request.correctedContentHash(),
                request.newRevisionId()
        ));
        return toMutationResult(result, principal.operatorId(), request.requestId());
    }

    public ProjectMemoryAdminMutationResult invalidate(ProjectMemoryInvalidateRequest request) {
        TrustedOperatorPrincipal principal = requirePrincipal();
        authorizer.authorize(principal, request.projectId(), ProjectMemoryMutationAction.INVALIDATE, request.requestId());
        ProjectMemoryGovernanceResult result = governancePort.invalidate(
                new ProjectMemoryGovernancePort.ProjectMemoryInvalidateCommand(
                        request.projectId(),
                        request.memoryId(),
                        request.revisionId(),
                        request.expectedMemoryRowVersion(),
                        request.expectedRevisionRowVersion()
                ));
        return toMutationResult(result, principal.operatorId(), request.requestId());
    }

    public ProjectMemoryAdminMutationResult softDelete(ProjectMemorySoftDeleteRequest request) {
        TrustedOperatorPrincipal principal = requirePrincipal();
        authorizer.authorize(principal, request.projectId(), ProjectMemoryMutationAction.SOFT_DELETE, request.requestId());
        ProjectMemoryGovernanceResult result = governancePort.softDelete(
                new ProjectMemoryGovernancePort.ProjectMemorySoftDeleteCommand(
                        request.projectId(),
                        request.memoryId(),
                        request.expectedMemoryRowVersion()
                ));
        return toMutationResult(result, principal.operatorId(), request.requestId());
    }

    private TrustedOperatorPrincipal requirePrincipal() {
        if (principalProvider.isEmpty()) {
            throw new ProjectMemoryMutationDisabledException(
                    "project memory mutations are disabled until a trusted principal provider is configured"
            );
        }
        return principalProvider.get().current()
                .orElseThrow(() -> new ProjectMemoryMutationDeniedException("trusted operator principal is required"));
    }

    private static ProjectMemoryAdminMutationResult toMutationResult(
            ProjectMemoryGovernanceResult result,
            String operatorId,
            String requestId
    ) {
        return new ProjectMemoryAdminMutationResult(
                result.memoryId(),
                result.revisionId(),
                result.memoryRowVersion(),
                result.revisionRowVersion(),
                result.revisionStatus(),
                result.memoryDeleted(),
                operatorId,
                requestId
        );
    }

    public record ProjectMemoryConfirmRequest(
            String projectId,
            String memoryId,
            String revisionId,
            long expectedMemoryRowVersion,
            long expectedRevisionRowVersion,
            String requestId,
            String requestedActor
    ) {}

    public record ProjectMemoryCorrectRequest(
            String projectId,
            String memoryId,
            String revisionId,
            long expectedMemoryRowVersion,
            long expectedRevisionRowVersion,
            String correctedTitle,
            String correctedSummary,
            String correctedContentJson,
            String correctedContentHash,
            String newRevisionId,
            String requestId,
            String requestedActor
    ) {}

    public record ProjectMemoryInvalidateRequest(
            String projectId,
            String memoryId,
            String revisionId,
            long expectedMemoryRowVersion,
            long expectedRevisionRowVersion,
            String requestId,
            String requestedActor
    ) {}

    public record ProjectMemorySoftDeleteRequest(
            String projectId,
            String memoryId,
            long expectedMemoryRowVersion,
            String requestId,
            String requestedActor
    ) {}
}
