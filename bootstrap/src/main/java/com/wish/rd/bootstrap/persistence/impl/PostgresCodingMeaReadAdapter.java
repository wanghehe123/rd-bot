package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.audit.AuditedTaskState;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.engine.requirement.query.CodingMeaBadRequestException;
import com.wish.rd.engine.requirement.query.CodingMeaCursor;
import com.wish.rd.engine.requirement.query.CodingMeaNotFoundException;
import com.wish.rd.engine.requirement.query.CodingMeaReadPort;
import com.wish.rd.engine.requirement.query.CodingMeaSnapshot;
import com.wish.rd.engine.requirement.remediation.AgentRemediationRoundStore;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationKind;
import com.wish.rd.engine.requirement.remediation.model.AgentRemediationRound;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * PostgreSQL read adapter for one Coding MEA snapshot.
 *
 * <p>Must not be {@code final}: {@code @Transactional} uses CGLIB subclassing.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresCodingMeaReadAdapter implements CodingMeaReadPort {

    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final RequirementStageCommandStore commandStore;
    private final ManagerDecisionStore managerDecisionStore;
    private final AuditedTaskStateStore auditedTaskStateStore;
    private final AgentRemediationRoundStore remediationRoundStore;
    private final HostVerificationStore hostVerificationStore;
    private final TaskRetryAttemptBindingStore retryBindingStore;

    /**
     * Creates the read-only adapter.
     *
     * @param registry task registry
     * @param stageRunStore stage runs
     * @param commandStore commands
     * @param managerDecisionStore decisions
     * @param auditedTaskStateStore audited state
     * @param remediationRoundStore remediations (optional — no production store bean yet)
     * @param hostVerificationStore host verify runs (optional)
     * @param retryBindingStore retry bindings (optional)
     */
    @Autowired
    public PostgresCodingMeaReadAdapter(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            RequirementStageCommandStore commandStore,
            ManagerDecisionStore managerDecisionStore,
            AuditedTaskStateStore auditedTaskStateStore,
            ObjectProvider<AgentRemediationRoundStore> remediationRoundStore,
            ObjectProvider<HostVerificationStore> hostVerificationStore,
            ObjectProvider<TaskRetryAttemptBindingStore> retryBindingStore
    ) {
        this(
                registry,
                stageRunStore,
                commandStore,
                managerDecisionStore,
                auditedTaskStateStore,
                remediationRoundStore.getIfAvailable(),
                hostVerificationStore.getIfAvailable(),
                retryBindingStore.getIfAvailable()
        );
    }

    /**
     * Test / direct wiring with optional nullable stores.
     */
    public PostgresCodingMeaReadAdapter(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            RequirementStageCommandStore commandStore,
            ManagerDecisionStore managerDecisionStore,
            AuditedTaskStateStore auditedTaskStateStore,
            AgentRemediationRoundStore remediationRoundStore,
            HostVerificationStore hostVerificationStore,
            TaskRetryAttemptBindingStore retryBindingStore
    ) {
        this.registry = registry;
        this.stageRunStore = stageRunStore;
        this.commandStore = commandStore;
        this.managerDecisionStore = managerDecisionStore;
        this.auditedTaskStateStore = auditedTaskStateStore;
        this.remediationRoundStore = remediationRoundStore;
        this.hostVerificationStore = hostVerificationStore;
        this.retryBindingStore = retryBindingStore;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CodingMeaSnapshot readSnapshot(String taskId, String codingStageRunId, int limit, String cursor) {
        int pageSize = limit <= 0 ? 50 : limit;
        if (pageSize < 1 || pageSize > 100) {
            throw new CodingMeaBadRequestException("limit must be between 1 and 100");
        }
        RdRequirementTask task = requireRequirementTask(taskId);
        List<AgentStageRun> stages = stageRunStore.listByTask(task.taskId()).stream()
                .filter(stage -> task.taskId().equals(stage.taskId()))
                .toList();
        String selected = selectCodingStage(task.taskId(), codingStageRunId, stages);
        boolean missing = selected.isBlank();
        long afterCreatedAt = 0L;
        String afterId = "";
        if (cursor != null && !cursor.isBlank()) {
            CodingMeaCursor.Position position = CodingMeaCursor.decode(cursor, task.taskId(), selected);
            afterCreatedAt = position.createdAtEpochMillis();
            afterId = position.commandId();
        }
        List<RequirementStageCommand> fetched = missing
                ? List.of()
                : commandStore.listByTaskAfter(task.taskId(), afterCreatedAt, afterId, pageSize + 1);
        boolean hasMore = fetched.size() > pageSize;
        List<RequirementStageCommand> page = hasMore ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = "";
        if (hasMore && !page.isEmpty()) {
            RequirementStageCommand last = page.getLast();
            nextCursor = CodingMeaCursor.encode(
                    task.taskId(), selected, last.createdAtEpochMillis(), last.commandId());
        }
        List<ManagerDecision> decisions = managerDecisionStore.listByTask(task.taskId());
        Map<Long, AuditedTaskState> revisions = new HashMap<>();
        for (ManagerDecision decision : decisions) {
            auditedTaskStateStore.findRevision(task.taskId(), decision.stateVersion())
                    .filter(revision -> task.taskId().equals(revision.taskId()))
                    .ifPresent(revision -> revisions.put(decision.stateVersion(), revision));
        }
        List<AgentRemediationRound> remediations = new ArrayList<>();
        if (remediationRoundStore != null) {
            for (AgentRemediationKind kind : AgentRemediationKind.values()) {
                remediations.addAll(remediationRoundStore.listByTask(task.taskId(), kind));
            }
        }
        List<TaskRetryAttemptBinding> bindings = new ArrayList<>();
        if (retryBindingStore != null) {
            Set<String> seen = new HashSet<>();
            for (AgentStageRun stage : stages) {
                retryBindingStore.findByStageRunId(stage.stageRunId()).ifPresent(binding -> {
                    if (seen.add(binding.bindingId())) {
                        bindings.add(binding);
                    }
                });
            }
        }
        return new CodingMeaSnapshot(
                System.currentTimeMillis(),
                task.taskId(),
                task.version(),
                task.status().name(),
                task.paused(),
                selected,
                missing,
                auditedTaskStateStore.head(task.taskId()).orElse(null),
                revisions,
                stages,
                page,
                new HashSet<>(commandStore.listIdsByTask(task.taskId())),
                hasMore,
                nextCursor,
                decisions,
                remediations,
                hostVerificationStore == null ? List.of() : hostVerificationStore.listByTask(task.taskId()),
                auditedTaskStateStore.listAuditRuns(task.taskId()),
                bindings
        );
    }

    private RdRequirementTask requireRequirementTask(String taskId) {
        RdTask task;
        try {
            task = registry.getTask(taskId);
        } catch (NoSuchElementException missing) {
            throw new CodingMeaNotFoundException("task not found");
        }
        if (!(task instanceof RdRequirementTask requirementTask)) {
            throw new CodingMeaNotFoundException("coding MEA is requirement-only");
        }
        return requirementTask;
    }

    private String selectCodingStage(String taskId, String requested, List<AgentStageRun> stages) {
        String explicit = requested == null ? "" : requested.strip();
        if (!explicit.isBlank()) {
            AgentStageRun match = stages.stream()
                    .filter(stage -> explicit.equals(stage.stageRunId()))
                    .findFirst()
                    .orElseThrow(() -> new CodingMeaNotFoundException("coding stage not found"));
            if (!taskId.equals(match.taskId()) || match.role() != AgentRole.CODING_AGENT) {
                throw new CodingMeaNotFoundException("coding stage not found");
            }
            return match.stageRunId();
        }
        return stages.stream()
                .filter(stage -> stage.role() == AgentRole.CODING_AGENT && taskId.equals(stage.taskId()))
                .max(Comparator.comparingInt(AgentStageRun::attemptNo)
                        .thenComparingLong(AgentStageRun::createTimeEpochMillis)
                        .thenComparing(AgentStageRun::stageRunId))
                .map(AgentStageRun::stageRunId)
                .orElse("");
    }
}
