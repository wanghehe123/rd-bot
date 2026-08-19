package com.wish.rd.engine.requirement.job.model;

import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One durable, bounded requirement-delivery stage action.
 *
 * <p>The command carries the task version and fencing token read by the worker so a late
 * stage result cannot revive a newer task snapshot. Lease and retry fields are persisted with
 * the command rather than held in a thread or executor queue. The canonical record constructor
 * may rehydrate a historical zero-fence row only; all new commands must use {@link #pending}
 * and therefore carry a positive fencing token.
 */
public record RequirementStageCommand(
        String commandId,
        String taskId,
        long taskVersion,
        long fencingToken,
        String role,
        String stage,
        int attemptNo,
        int maxAttempts,
        long deadlineEpochMillis,
        ScheduleResourceClass resourceClass,
        Set<ScheduleResourceClass> resourceRequirements,
        String projectId,
        String providerId,
        int priorityRank,
        Status status,
        String leaseOwner,
        long leaseUntilEpochMillis,
        long nextVisibleAtEpochMillis,
        String lastError,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        String policyRunId,
        String retryCheckpointId,
        long businessGeneration,
        String targetRetryBindingId,
        String remediationRoundId,
        AgentRemediationKind remediationKind,
        int remediationNo,
        String remediationSourceStageRunId,
        String remediationRequestJson,
        String remediationRequestHash
) {

    private static final int MAX_REMEDIATION_REQUEST_BYTES = 65_536;

    /** Durable stage lifecycle. */
    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED_RETRYABLE,
        DEAD_LETTERED,
        CANCELLED
    }

    public RequirementStageCommand {
        commandId = require(commandId, "commandId");
        taskId = require(taskId, "taskId");
        if (taskVersion < 0L) {
            throw new IllegalArgumentException("taskVersion must not be negative");
        }
        if (fencingToken < 0L) {
            throw new IllegalArgumentException("fencingToken must not be negative");
        }
        role = safe(role);
        stage = require(stage, "stage");
        attemptNo = Math.max(0, attemptNo);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        deadlineEpochMillis = Math.max(0L, deadlineEpochMillis);
        resourceClass = resourceClass == null ? ScheduleResourceClass.GENERIC : resourceClass;
        resourceRequirements = normalizeResourceRequirements(resourceRequirements, resourceClass);
        if (resourceClass == ScheduleResourceClass.GENERIC && resourceRequirements.size() > 1) {
            resourceClass = resourceRequirements.stream()
                    .filter(resource -> resource != ScheduleResourceClass.GENERIC)
                    .findFirst()
                    .orElse(ScheduleResourceClass.GENERIC);
        }
        projectId = projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
        providerId = safe(providerId);
        priorityRank = Math.max(0, priorityRank);
        status = status == null ? Status.PENDING : status;
        leaseOwner = safe(leaseOwner);
        leaseUntilEpochMillis = Math.max(0L, leaseUntilEpochMillis);
        nextVisibleAtEpochMillis = Math.max(0L, nextVisibleAtEpochMillis);
        lastError = safe(lastError);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
        policyRunId = safe(policyRunId);
        retryCheckpointId = safe(retryCheckpointId);
        businessGeneration = Math.max(0L, businessGeneration);
        targetRetryBindingId = safe(targetRetryBindingId);
        remediationRoundId = safe(remediationRoundId);
        remediationSourceStageRunId = safe(remediationSourceStageRunId);
        remediationRequestJson = safe(remediationRequestJson);
        remediationRequestHash = safe(remediationRequestHash).toLowerCase(java.util.Locale.ROOT);
        validateGenerationIdentity(retryCheckpointId, businessGeneration, targetRetryBindingId, stage,
                remediationRoundId, remediationKind, remediationNo);
        validateRemediationRequest(remediationRoundId, remediationSourceStageRunId,
                remediationRequestJson, remediationRequestHash);
    }

    /** Compatibility constructor for rows written before immutable remediation requests. */
    public RequirementStageCommand(
            String commandId, String taskId, long taskVersion, long fencingToken, String role, String stage,
            int attemptNo, int maxAttempts, long deadlineEpochMillis, ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements, String projectId, String providerId, int priorityRank,
            Status status, String leaseOwner, long leaseUntilEpochMillis, long nextVisibleAtEpochMillis,
            String lastError, long createdAtEpochMillis, long updatedAtEpochMillis, String policyRunId,
            String retryCheckpointId, long businessGeneration, String targetRetryBindingId,
            String remediationRoundId, AgentRemediationKind remediationKind, int remediationNo
    ) {
        this(commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId, priorityRank,
                status, leaseOwner, leaseUntilEpochMillis, nextVisibleAtEpochMillis, lastError,
                createdAtEpochMillis, updatedAtEpochMillis, policyRunId, retryCheckpointId,
                businessGeneration, targetRetryBindingId, remediationRoundId, remediationKind, remediationNo,
                "", "", "");
    }

    /** Backward-compatible canonical constructor for normal/checkpoint commands. */
    public RequirementStageCommand(
            String commandId, String taskId, long taskVersion, long fencingToken, String role, String stage,
            int attemptNo, int maxAttempts, long deadlineEpochMillis, ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements, String projectId, String providerId, int priorityRank,
            Status status, String leaseOwner, long leaseUntilEpochMillis, long nextVisibleAtEpochMillis,
            String lastError, long createdAtEpochMillis, long updatedAtEpochMillis, String policyRunId,
            String retryCheckpointId, long businessGeneration, String targetRetryBindingId
    ) {
        this(commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId, priorityRank,
                status, leaseOwner, leaseUntilEpochMillis, nextVisibleAtEpochMillis, lastError,
                createdAtEpochMillis, updatedAtEpochMillis, policyRunId, retryCheckpointId,
                businessGeneration, targetRetryBindingId, "", null, 0);
    }

    /**
     * Backward-compatible canonical constructor for rows and callers created before policy
     * authorization references were added. Legacy non-role commands rehydrate with no policy run.
     */
    public RequirementStageCommand(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements,
            String projectId,
            String providerId,
            int priorityRank,
            Status status,
            String leaseOwner,
            long leaseUntilEpochMillis,
            long nextVisibleAtEpochMillis,
            String lastError,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
        this(commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId,
                priorityRank, status, leaseOwner, leaseUntilEpochMillis, nextVisibleAtEpochMillis,
                lastError, createdAtEpochMillis, updatedAtEpochMillis, "", "", 0L, "");
    }

    /**
     * Backward-compatible command constructor for persisted callers that only provide one
     * resource class. New enqueue paths must use the canonical constructor or set-aware
     * {@link #pending(String, String, long, long, String, String, int, int, long,
     * ScheduleResourceClass, Set, String, String, String, long)} factory.
     */
    public RequirementStageCommand(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            String projectId,
            String providerId,
            int priorityRank,
            Status status,
            String leaseOwner,
            long leaseUntilEpochMillis,
            long nextVisibleAtEpochMillis,
            String lastError,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
        this(
                commandId,
                taskId,
                taskVersion,
                fencingToken,
                role,
                stage,
                attemptNo,
                maxAttempts,
                deadlineEpochMillis,
                resourceClass,
                Set.of(resourceClass == null ? ScheduleResourceClass.GENERIC : resourceClass),
                projectId,
                providerId,
                priorityRank,
                status,
                leaseOwner,
                leaseUntilEpochMillis,
                nextVisibleAtEpochMillis,
                lastError,
                createdAtEpochMillis,
                updatedAtEpochMillis,
                "", "", 0L, ""
        );
    }

    /** Compatibility constructor for policy-bound commands predating checkpoint retry identity. */
    public RequirementStageCommand(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements,
            String projectId,
            String providerId,
            int priorityRank,
            Status status,
            String leaseOwner,
            long leaseUntilEpochMillis,
            long nextVisibleAtEpochMillis,
            String lastError,
            long createdAtEpochMillis,
            long updatedAtEpochMillis,
            String policyRunId
    ) {
        this(commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId,
                priorityRank, status, leaseOwner, leaseUntilEpochMillis, nextVisibleAtEpochMillis,
                lastError, createdAtEpochMillis, updatedAtEpochMillis, policyRunId, "", 0L, "");
    }

    /** Creates a new pending command. */
    public static RequirementStageCommand pending(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            String projectId,
            String providerId,
            String priority,
            long nowEpochMillis
    ) {
        requireNewCommandFence(fencingToken);
        return pending(
                commandId,
                taskId,
                taskVersion,
                fencingToken,
                role,
                stage,
                attemptNo,
                maxAttempts,
                deadlineEpochMillis,
                resourceClass,
                Set.of(resourceClass == null ? ScheduleResourceClass.GENERIC : resourceClass),
                projectId,
                providerId,
                priority,
                nowEpochMillis
        );
    }

    /** Creates a new pending command with every resource token required for admission. */
    public static RequirementStageCommand pending(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements,
            String projectId,
            String providerId,
            String priority,
            long nowEpochMillis
    ) {
        requireNewCommandFence(fencingToken);
        return pending(commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo,
                maxAttempts, deadlineEpochMillis, resourceClass, resourceRequirements, projectId,
                providerId, priority, "", nowEpochMillis);
    }

    /** Creates a new pending command bound to one immutable policy-ledger generation. */
    public static RequirementStageCommand pending(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements,
            String projectId,
            String providerId,
            String priority,
            String policyRunId,
            long nowEpochMillis
    ) {
        requireNewCommandFence(fencingToken);
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId,
                priorityRank(priority), Status.PENDING, "", 0L, nowEpochMillis, "",
                nowEpochMillis, nowEpochMillis, policyRunId, "", 0L, ""
        );
    }

    /** Creates a checkpoint-bound pending command with its immutable business generation identity. */
    public static RequirementStageCommand pending(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int attemptNo,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements,
            String projectId,
            String providerId,
            String priority,
            String policyRunId,
            String retryCheckpointId,
            long businessGeneration,
            String targetRetryBindingId,
            long nowEpochMillis
    ) {
        requireNewCommandFence(fencingToken);
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId,
                priorityRank(priority), Status.PENDING, "", 0L, nowEpochMillis, "",
                nowEpochMillis, nowEpochMillis, policyRunId, retryCheckpointId, businessGeneration,
                targetRetryBindingId);
    }

    /** Creates a PI-remediation command bound to an already claimed durable round. */
    public static RequirementStageCommand remediationPending(
            String commandId,
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            int maxAttempts,
            long deadlineEpochMillis,
            ScheduleResourceClass resourceClass,
            Set<ScheduleResourceClass> resourceRequirements,
            String projectId,
            String providerId,
            String priority,
            String policyRunId,
            String remediationRoundId,
            AgentRemediationKind remediationKind,
            int remediationNo,
            String remediationSourceStageRunId,
            String remediationRequestJson,
            String remediationRequestHash,
            long nowEpochMillis
    ) {
        requireNewCommandFence(fencingToken);
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, 0, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId,
                priorityRank(priority), Status.PENDING, "", 0L, nowEpochMillis, "",
                nowEpochMillis, nowEpochMillis, policyRunId, "", 0L, "",
                remediationRoundId, remediationKind, remediationNo, remediationSourceStageRunId,
                remediationRequestJson, remediationRequestHash);
    }

    /** Claims this command for one worker. */
    public RequirementStageCommand claimed(String owner, long leaseUntil, long now) {
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo + 1, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId, priorityRank,
                Status.RUNNING, require(owner, "leaseOwner"), leaseUntil, nextVisibleAtEpochMillis,
                "", createdAtEpochMillis, now, policyRunId, retryCheckpointId, businessGeneration,
                targetRetryBindingId, remediationRoundId, remediationKind, remediationNo,
                remediationSourceStageRunId, remediationRequestJson, remediationRequestHash
        );
    }

    /** Extends a worker lease. */
    public RequirementStageCommand heartbeat(long leaseUntil, long now) {
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId, priorityRank,
                status, leaseOwner, leaseUntil, nextVisibleAtEpochMillis, lastError, createdAtEpochMillis, now,
                policyRunId, retryCheckpointId, businessGeneration, targetRetryBindingId,
                remediationRoundId, remediationKind, remediationNo,
                remediationSourceStageRunId, remediationRequestJson, remediationRequestHash
        );
    }

    /** Marks a command successfully completed. */
    public RequirementStageCommand succeeded(long now) {
        return finished(Status.SUCCEEDED, "", now);
    }

    /** Marks a failed command retryable or dead-lettered at the attempt limit. */
    public RequirementStageCommand failed(String error, long now) {
        Status target = attemptNo >= maxAttempts ? Status.DEAD_LETTERED : Status.FAILED_RETRYABLE;
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId, priorityRank,
                target, "", 0L, now, error, createdAtEpochMillis, now, policyRunId,
                retryCheckpointId, businessGeneration, targetRetryBindingId,
                remediationRoundId, remediationKind, remediationNo,
                remediationSourceStageRunId, remediationRequestJson, remediationRequestHash
        );
    }

    /** Returns whether this command can be claimed at the supplied time. */
    public boolean claimable(long now) {
        return (status == Status.PENDING || status == Status.FAILED_RETRYABLE
                || (status == Status.RUNNING && leaseUntilEpochMillis <= now))
                && nextVisibleAtEpochMillis <= now
                && (deadlineEpochMillis <= 0L || deadlineEpochMillis > now)
                && attemptNo < maxAttempts;
    }

    /** Returns whether a running lease has expired. */
    public boolean leaseExpired(long now) {
        return status == Status.RUNNING && leaseUntilEpochMillis <= now;
    }

    /** Returns whether this command requires the supplied resource token to be admitted. */
    public boolean requires(ScheduleResourceClass resource) {
        return resource != null && resourceRequirements.contains(resource);
    }

    /** Serializes the durable resource requirements in a stable, schema-independent format. */
    public String encodedResourceRequirements() {
        return resourceRequirements.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Reads durable resource requirements while retaining the legacy single-class fallback for
     * rows written before multi-resource admission was introduced.
     *
     * @param encoded comma-separated resource enum names
     * @param fallback persisted legacy resource class
     * @return normalized resource requirements
     */
    public static Set<ScheduleResourceClass> decodeResourceRequirements(
            String encoded,
            ScheduleResourceClass fallback
    ) {
        EnumSet<ScheduleResourceClass> decoded = EnumSet.noneOf(ScheduleResourceClass.class);
        if (encoded != null && !encoded.isBlank()) {
            for (String token : encoded.split(",")) {
                try {
                    decoded.add(ScheduleResourceClass.valueOf(token.strip()));
                } catch (IllegalArgumentException ignored) {
                    // Unknown values are ignored so a malformed historical row falls back safely.
                }
            }
        }
        return normalizeResourceRequirements(decoded, fallback);
    }

    private RequirementStageCommand finished(Status target, String error, long now) {
        return new RequirementStageCommand(
                commandId, taskId, taskVersion, fencingToken, role, stage, attemptNo, maxAttempts,
                deadlineEpochMillis, resourceClass, resourceRequirements, projectId, providerId, priorityRank,
                target, "", 0L, nextVisibleAtEpochMillis, error, createdAtEpochMillis, now, policyRunId,
                retryCheckpointId, businessGeneration, targetRetryBindingId,
                remediationRoundId, remediationKind, remediationNo,
                remediationSourceStageRunId, remediationRequestJson, remediationRequestHash
        );
    }

    private static void validateRemediationRequest(
            String remediationRoundId,
            String sourceStageRunId,
            String requestJson,
            String requestHash
    ) {
        if (remediationRoundId.isBlank()) {
            if (!sourceStageRunId.isBlank() || !requestJson.isBlank() || !requestHash.isBlank()) {
                throw new IllegalArgumentException("normal command must not carry a remediation request");
            }
            return;
        }
        if (sourceStageRunId.isBlank() || requestJson.isBlank()
                || !requestHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("remediation command requires source/request/hash identity");
        }
        String canonical = CanonicalJsonSha256.canonicalize(requestJson);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_REMEDIATION_REQUEST_BYTES) {
            throw new IllegalArgumentException("remediation request exceeds protocol limit");
        }
        if (!canonical.equals(requestJson) || !CanonicalJsonSha256.digest(canonical).equals(requestHash)) {
            throw new IllegalArgumentException("remediation request hash mismatch");
        }
    }

    private static Set<ScheduleResourceClass> normalizeResourceRequirements(
            Set<ScheduleResourceClass> values,
            ScheduleResourceClass fallback
    ) {
        EnumSet<ScheduleResourceClass> normalized = EnumSet.noneOf(ScheduleResourceClass.class);
        if (values != null) {
            for (ScheduleResourceClass value : values) {
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(fallback == null ? ScheduleResourceClass.GENERIC : fallback);
        }
        if (normalized.size() > 1) {
            normalized.remove(ScheduleResourceClass.GENERIC);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static int priorityRank(String value) {
        String priority = safe(value).toUpperCase();
        if (priority.startsWith("P")) {
            try {
                return Math.max(0, Integer.parseInt(priority.substring(1)));
            } catch (NumberFormatException ignored) {
                // Fall through to the conservative lowest priority.
            }
        }
        return 2;
    }

    private static void requireNewCommandFence(long fencingToken) {
        if (fencingToken <= 0L) {
            throw new IllegalArgumentException("new stage command fencingToken must be positive");
        }
    }

    private static void validateGenerationIdentity(
            String checkpointId, long generation, String targetBindingId, String stage,
            String remediationRoundId, AgentRemediationKind remediationKind, int remediationNo
    ) {
        boolean retry = !checkpointId.isBlank();
        boolean remediation = !remediationRoundId.isBlank();
        if (retry && remediation) {
            throw new IllegalArgumentException("checkpoint and remediation command identities are mutually exclusive");
        }
        if (remediation) {
            if (generation != 0L || !targetBindingId.isBlank() || remediationKind == null
                    || remediationNo < 1 || remediationNo > remediationKind.maximumRounds()) {
                throw new IllegalArgumentException("invalid remediation command identity");
            }
            try {
                Long.parseLong(remediationRoundId);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("remediation round id must be numeric", exception);
            }
            return;
        }
        if (remediationKind != null || remediationNo != 0) {
            throw new IllegalArgumentException("normal/checkpoint command must not carry partial remediation identity");
        }
        if (!retry) {
            if (generation != 0L || !targetBindingId.isBlank()) {
                throw new IllegalArgumentException("normal command must not carry retry identity");
            }
            return;
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("retry command businessGeneration must be positive");
        }
        try {
            if (Long.parseLong(checkpointId) != generation) {
                throw new IllegalArgumentException("retry command businessGeneration must equal retryCheckpointId");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("retry command checkpoint id must be numeric", exception);
        }
        boolean attemptTarget = "AI_REVIEW".equals(stage) || stage.startsWith("ROLE_EXECUTION:");
        if (attemptTarget && targetBindingId.isBlank()) {
            throw new IllegalArgumentException("retry attempt command requires a target retry binding");
        }
        if (!attemptTarget && !targetBindingId.isBlank()) {
            throw new IllegalArgumentException("retry infrastructure command must not carry a target retry binding");
        }
    }

    private static String require(String value, String name) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
