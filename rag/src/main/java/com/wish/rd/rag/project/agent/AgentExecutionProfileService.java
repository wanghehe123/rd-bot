package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;

import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validates registered execution strategies and resolves authorized bindings. */
public final class AgentExecutionProfileService {

    private static final Set<String> SUPPORTED_ROLES = Set.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"
    );

    private final AgentExecutionProfileStore store;

    public AgentExecutionProfileService(AgentExecutionProfileStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public AgentExecutionProfile register(AgentExecutionProfile profile) {
        validate(profile);
        store.find(profile.profileId())
                .filter(existing -> !existing.projectId().equals(profile.projectId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("profileId belongs to another project");
                });
        return store.save(profile);
    }

    public void bindProjectDefault(String projectId, String role, String profileId) {
        String safeProjectId = requireText(projectId, "projectId");
        String safeRole = requireRole(role);
        AgentExecutionProfile profile = requireProfile(profileId);
        requireProfileMatches(profile, safeProjectId, safeRole);
        requireEnabled(profile);
        store.bindProjectDefault(safeProjectId, safeRole, profile.profileId());
    }

    public void setTaskOverride(String taskId, String projectId, String role, String profileId) {
        String safeTaskId = requireText(taskId, "taskId");
        String safeProjectId = requireText(projectId, "projectId");
        String safeRole = requireRole(role);
        AgentExecutionProfile profile = requireProfile(profileId);
        requireProfileMatches(profile, safeProjectId, safeRole);
        requireEnabled(profile);
        store.setTaskOverride(safeTaskId, safeProjectId, safeRole, profile.profileId());
    }

    public Optional<AgentExecutionProfile> resolve(String projectId, String taskId, String role) {
        String safeProjectId = requireText(projectId, "projectId");
        String safeTaskId = requireText(taskId, "taskId");
        String safeRole = requireRole(role);
        Optional<String> profileId = store.findTaskOverride(safeTaskId, safeRole)
                .or(() -> store.findProjectDefault(safeProjectId, safeRole));
        if (profileId.isEmpty()) {
            return Optional.empty();
        }
        AgentExecutionProfile profile = requireProfile(profileId.get());
        requireProfileMatches(profile, safeProjectId, safeRole);
        return profile.enabled() ? Optional.of(profile) : Optional.empty();
    }

    public List<AgentExecutionProfile> listByProject(String projectId) {
        return store.listByProject(requireText(projectId, "projectId"));
    }

    /**
     * Returns the project default profile for a role when a binding exists.
     *
     * @param projectId project id
     * @param role delivery role
     * @return bound profile when present and matching
     */
    public Optional<AgentExecutionProfile> findProjectDefault(String projectId, String role) {
        String safeProjectId = requireText(projectId, "projectId");
        String safeRole = requireRole(role);
        return store.findProjectDefault(safeProjectId, safeRole)
                .flatMap(store::find)
                .filter(profile -> profile.projectId().equals(safeProjectId))
                .filter(profile -> profile.role().equals(safeRole));
    }

    public void clearTaskOverride(String taskId, String role) {
        store.clearTaskOverride(requireText(taskId, "taskId"), requireRole(role));
    }

    /** Returns only the explicit task override, without falling back to the project default. */
    public Optional<AgentExecutionProfile> findTaskOverride(
            String taskId,
            String projectId,
            String role
    ) {
        String safeTaskId = requireText(taskId, "taskId");
        String safeProjectId = requireText(projectId, "projectId");
        String safeRole = requireRole(role);
        return store.findTaskOverride(safeTaskId, safeRole)
                .map(this::requireProfile)
                .filter(profile -> profile.projectId().equals(safeProjectId))
                .filter(profile -> profile.role().equals(safeRole));
    }

    private AgentExecutionProfile requireProfile(String profileId) {
        String safeProfileId = requireText(profileId, "profileId");
        return store.find(safeProfileId)
                .orElseThrow(() -> new IllegalArgumentException("execution profile not found: " + safeProfileId));
    }

    private static void validate(AgentExecutionProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("execution profile must not be null");
        }
        requireText(profile.profileId(), "profileId");
        requireText(profile.projectId(), "projectId");
        requireRole(profile.role());
        requireText(profile.name(), "name");
        requireText(profile.providerProfileId(), "providerProfileId");
        requireText(profile.toolPolicyId(), "toolPolicyId");
    }

    private static void requireProfileMatches(
            AgentExecutionProfile profile,
            String projectId,
            String role
    ) {
        if (!profile.projectId().equals(projectId) || !profile.role().equals(role)) {
            throw new IllegalArgumentException("profile does not belong to the requested project and role");
        }
    }

    private static void requireEnabled(AgentExecutionProfile profile) {
        if (!profile.enabled()) {
            throw new IllegalArgumentException("execution profile is disabled");
        }
    }

    private static String requireRole(String role) {
        String normalized = requireText(role, "role").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ROLES.contains(normalized)) {
            throw new IllegalArgumentException("role must be a requirement delivery role");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
