package com.wish.rd.rag.project.runtime;

import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfileCommand;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Validates and resolves the server-verified Docker image for each project role. */
public final class ProjectRuntimeProfileService {

    public static final String SUPPORTED_AGENT_TYPE = "CLAUDE_CODE";
    public static final String VERIFIED_STATUS = "VERIFIED";
    /** Marker emitted only after the current restricted Dockerfile contract has been verified. */
    public static final String RUNTIME_PROFILE_CONTRACT_MARKER = "rd-bot-runtime-contract=v2";
    private static final Set<String> SUPPORTED_ROLES = Set.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"
    );
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9][A-Za-z0-9._-]*)?(?:@[A-Za-z0-9:+._-]+)?"
    );

    private final ProjectRuntimeProfileStore store;
    private final Consumer<String> projectValidator;

    public ProjectRuntimeProfileService(ProjectRuntimeProfileStore store) {
        this(store, ignored -> { });
    }

    public ProjectRuntimeProfileService(
            ProjectRuntimeProfileStore store,
            Consumer<String> projectValidator
    ) {
        this.store = java.util.Objects.requireNonNull(store, "store must not be null");
        this.projectValidator = projectValidator == null ? ignored -> { } : projectValidator;
    }

    /** Saves a profile only after its upload path has produced a verified Claude Code image. */
    public ProjectRuntimeProfile save(ProjectRuntimeProfileCommand command) {
        ProjectRuntimeProfileCommand safe = requireCommand(command);
        projectValidator.accept(safe.projectId());
        long now = System.currentTimeMillis();
        long createdAt = store.find(safe.projectId(), safe.role())
                .map(ProjectRuntimeProfile::createTimeEpochMillis)
                .filter(value -> value > 0L)
                .orElse(now);
        return store.save(new ProjectRuntimeProfile(
                safe.projectId(),
                safe.role(),
                safe.agentType(),
                safe.image(),
                safe.dockerfileArtifactUri(),
                safe.dockerfileSha256(),
                safe.dockerfileName(),
                VERIFIED_STATUS,
                safe.validationSummary(),
                createdAt,
                now
        ));
    }

    public Optional<ProjectRuntimeProfile> resolveVerified(String projectId, String role) {
        String safeProjectId = requireText(projectId, "projectId");
        String safeRole = requireRole(role);
        return store.find(safeProjectId, safeRole)
                .filter(profile -> VERIFIED_STATUS.equals(profile.validationStatus()))
                .filter(profile -> SUPPORTED_AGENT_TYPE.equals(profile.agentType()))
                .filter(ProjectRuntimeProfileService::usesCurrentRuntimeProfileContract);
    }

    /** Existing profiles without the marker predate the restricted upload policy and must be re-uploaded. */
    public static boolean usesCurrentRuntimeProfileContract(ProjectRuntimeProfile profile) {
        return profile != null && profile.validationSummary().contains(RUNTIME_PROFILE_CONTRACT_MARKER);
    }

    public List<ProjectRuntimeProfile> list(String projectId) {
        return store.list(requireText(projectId, "projectId"));
    }

    public boolean delete(String projectId, String role) {
        String safeProjectId = requireText(projectId, "projectId");
        projectValidator.accept(safeProjectId);
        return store.delete(safeProjectId, requireRole(role));
    }

    private static ProjectRuntimeProfileCommand requireCommand(ProjectRuntimeProfileCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("runtime profile command must not be null");
        }
        requireText(command.projectId(), "projectId");
        requireRole(command.role());
        if (!SUPPORTED_AGENT_TYPE.equals(command.agentType())) {
            throw new IllegalArgumentException("agentType must be CLAUDE_CODE");
        }
        if (!IMAGE_REFERENCE.matcher(command.image()).matches()) {
            throw new IllegalArgumentException("image must be a safe Docker image reference");
        }
        if (!command.dockerfileArtifactUri().startsWith("s3://")) {
            throw new IllegalArgumentException("dockerfileArtifactUri must be a private s3 URL");
        }
        if (!SHA256.matcher(command.dockerfileSha256()).matches()) {
            throw new IllegalArgumentException("dockerfileSha256 must be a SHA-256 hex digest");
        }
        if (command.dockerfileName().isBlank()) {
            throw new IllegalArgumentException("dockerfileName must not be blank");
        }
        if (command.validationSummary().isBlank()) {
            throw new IllegalArgumentException("validationSummary must not be blank");
        }
        if (command.validationSummary().length() > 4_000) {
            throw new IllegalArgumentException("validationSummary exceeds 4000 characters");
        }
        return command;
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
