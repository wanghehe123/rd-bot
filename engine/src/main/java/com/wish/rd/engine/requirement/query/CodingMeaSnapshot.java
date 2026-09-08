package com.wish.rd.engine.requirement.query;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.audit.AuditRun;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One consistent read of Coding MEA raw references. QueryEngine projects DTOs from this only.
 *
 * @param snapshotReadAtEpochMillis adapter clock
 * @param taskId owning task
 * @param taskVersion current task version
 * @param taskStatus current task status name
 * @param paused pause flag
 * @param selectedCodingStageRunId selected Coding stage, or blank when none
 * @param codingStageMissing true when the task has no Coding stage
 * @param head current audited head, or null
 * @param revisionsByVersion loaded revisions keyed by version; missing keys stay unavailable
 * @param stages task-owned agent stages
 * @param pageCommands commands on this page
 * @param knownCommandIds all command ids for the task (for OUTSIDE_PAGE)
 * @param commandsHasMore more commands exist after this page
 * @param nextCursor opaque cursor, or blank
 * @param decisions manager decisions
 * @param remediations remediation rounds
 * @param hostVerifications host verification runs
 * @param auditRuns audit runs
 * @param retryBindings retry bindings used for stage↔command links
 */
public record CodingMeaSnapshot(
        long snapshotReadAtEpochMillis,
        String taskId,
        long taskVersion,
        String taskStatus,
        boolean paused,
        String selectedCodingStageRunId,
        boolean codingStageMissing,
        AuditedTaskState head,
        Map<Long, AuditedTaskState> revisionsByVersion,
        List<AgentStageRun> stages,
        List<RequirementStageCommand> pageCommands,
        Set<String> knownCommandIds,
        boolean commandsHasMore,
        String nextCursor,
        List<ManagerDecision> decisions,
        List<AgentRemediationRound> remediations,
        List<HostVerificationRun> hostVerifications,
        List<AuditRun> auditRuns,
        List<TaskRetryAttemptBinding> retryBindings
) {
    public CodingMeaSnapshot {
        taskId = require(taskId, "taskId");
        taskStatus = taskStatus == null ? "" : taskStatus.strip();
        selectedCodingStageRunId = selectedCodingStageRunId == null ? "" : selectedCodingStageRunId.strip();
        revisionsByVersion = revisionsByVersion == null ? Map.of() : Map.copyOf(revisionsByVersion);
        stages = copy(stages);
        pageCommands = copy(pageCommands);
        knownCommandIds = knownCommandIds == null ? Set.of() : Set.copyOf(knownCommandIds);
        nextCursor = nextCursor == null ? "" : nextCursor.strip();
        decisions = copy(decisions);
        remediations = copy(remediations);
        hostVerifications = copy(hostVerifications);
        auditRuns = copy(auditRuns);
        retryBindings = copy(retryBindings);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static <T> List<T> copy(List<T> values) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("snapshot lists must not contain null");
        }
        return List.copyOf(values);
    }
}
