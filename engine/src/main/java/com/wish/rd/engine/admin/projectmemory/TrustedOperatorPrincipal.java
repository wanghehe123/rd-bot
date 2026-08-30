package com.wish.rd.engine.admin.projectmemory;

import java.util.Objects;
import java.util.Set;

/** Trusted operator identity supplied by the host, never from request bodies. */
public record TrustedOperatorPrincipal(
        String operatorId,
        Set<ProjectMemoryMutationCapability> capabilities,
        Set<String> governedProjectIds
) {
    public TrustedOperatorPrincipal {
        operatorId = required(operatorId, "operatorId");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        governedProjectIds = governedProjectIds == null ? Set.of() : Set.copyOf(governedProjectIds);
    }

    public boolean hasCapability(ProjectMemoryMutationCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    public boolean canGovernProject(String projectId) {
        String normalized = projectId == null ? "" : projectId.strip();
        return !normalized.isEmpty() && governedProjectIds.contains(normalized);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
