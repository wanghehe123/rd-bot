package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RequirementPolicyRunRow;
import com.wish.rd.bootstrap.persistence.entity.RequirementStageCommandRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import com.wish.rd.bootstrap.persistence.mapper.RequirementPolicyRunMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageCommandMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.bootstrap.threading.RequirementStageCommandFactory;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApprovalResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationPreparationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL producer for digest-bound policy approvals and their durable resume commands.
 *
 * <p>The transaction takes locks in policy-ledger then task order. The idempotent branch performs
 * no task, timeline, ledger, or enqueue write, so a retried request can only return the exact
 * command already bound to the approved ledger generation.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementPolicyTransactionAdapter implements RequirementPolicyTransactionPort {
    private static final String RESUME_ROLE = "REQUIREMENT_DELIVERY";
    private static final String RESUME_STAGE = "APPROVAL_RESUME";
    private static final String POLICY_EVALUATE_STAGE = "POLICY_EVALUATE";
    private static final String POLICY_APPLY_STAGE = "POLICY_APPLY";

    private final RequirementPolicyRunMapper policyMapper;
    private final RdTaskMapper taskMapper;
    private final RdTaskStatusEventMapper eventMapper;
    private final RequirementStageCommandMapper commandMapper;
    private final RequirementStageCommandFactory commandFactory;
    private final SnowflakeIdGenerator idGenerator;

    /** Creates the transactional approval producer from its PostgreSQL mappers and command factory. */
    public PostgresRequirementPolicyTransactionAdapter(
            RequirementPolicyRunMapper policyMapper,
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper eventMapper,
            RequirementStageCommandMapper commandMapper,
            RequirementStageCommandFactory commandFactory,
            SnowflakeIdGenerator idGenerator
    ) {
        this.policyMapper = Objects.requireNonNull(policyMapper, "policyMapper must not be null");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
        this.commandMapper = Objects.requireNonNull(commandMapper, "commandMapper must not be null");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
    }

    /**
     * Inserts the PLAN_READY ledger before its FK-bound evaluate command in one transaction.
     *
     * <p>The task row serializes competing preparation attempts for the same source generation.
     * Nothing is claimable until the transaction commits, so PostgreSQL never exposes an orphan
     * command whose {@code policy_run_id} has no ledger row.
     */
    @Override
    @Transactional
    public RequirementPolicyEvaluationPreparationResult prepareEvaluation(
            RequirementPolicyEvaluationProposal proposal,
            RequirementStageCommand command,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(command, "policyEvaluateCommand must not be null");
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }
        requirePreparationCommand(proposal, command);
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(command.taskId()));
        requirePlanGeneratedTask(task, proposal);
        RequirementPolicyRun requested = RequirementPolicyEvaluationPreparationSupport.planReadyLedger(
                command, proposal.planJson(), proposal.planDigest(), nowEpochMillis);
        RequirementPolicyRunRow before = policyMapper.findById(PostgresPersistenceSupport.parseId(requested.id()));
        if (before == null) {
            // A zero row count can be an exact concurrent/re-entrant replay. Re-read under the
            // task lock and validate the immutable generation semantically below.
            policyMapper.insertIfAbsent(PostgresRequirementPolicyRunStore.toRow(requested));
        }
        RequirementPolicyRunRow effectiveLedgerRow = policyMapper.findById(
                PostgresPersistenceSupport.parseId(requested.id()));
        if (effectiveLedgerRow == null) {
            throw new IllegalStateException("policy preparation ledger is missing: " + requested.id());
        }
        RequirementPolicyRun effectiveLedger = PostgresRequirementPolicyRunStore.toModel(effectiveLedgerRow);
        if (!RequirementPolicyEvaluationPreparationSupport.samePreparedLedgerGeneration(effectiveLedger, requested)) {
            throw new IllegalStateException("policy preparation ledger conflicts: " + requested.id());
        }
        RequirementStageCommandRow existingRow = commandMapper.find(
                PostgresPersistenceSupport.parseId(command.taskId()), RESUME_ROLE, POLICY_EVALUATE_STAGE);
        if (existingRow == null) {
            if (commandMapper.enqueue(toRow(command)) != 1) {
                throw new IllegalStateException("policy-evaluate command enqueue conflicted: " + command.commandId());
            }
            existingRow = commandMapper.find(
                    PostgresPersistenceSupport.parseId(command.taskId()), RESUME_ROLE, POLICY_EVALUATE_STAGE);
        }
        if (existingRow == null) {
            throw new IllegalStateException("policy-evaluate command is missing after preparation: " + command.commandId());
        }
        RequirementStageCommand effectiveCommand = toCommand(existingRow);
        if (!effectiveCommand.equals(command)) {
            throw new IllegalStateException("policy-evaluate command conflicts: " + command.commandId());
        }
        return new RequirementPolicyEvaluationPreparationResult(effectiveLedger, effectiveCommand);
    }

    /**
     * Approves a locked waiting ledger generation and atomically creates its resume command.
     *
     * @param command digest- and concurrency-bound approval request
     * @param actor host-controlled service actor for the approval audit trail
     * @param nowEpochMillis Host clock
     * @return durable approval result
     */
    @Override
    @Transactional
    public RequirementPolicyApprovalResult approve(
            ApproveRequirementPolicyCommand command, String actor, long nowEpochMillis
    ) {
        Objects.requireNonNull(command, "command must not be null");
        String approvedBy = require(actor, "actor");
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }
        RequirementPolicyRunRow lockedRow = policyMapper.lockForUpdate(
                PostgresPersistenceSupport.parseId(command.policyRunId()));
        if (lockedRow == null) {
            throw new IllegalStateException("policy run is missing: " + command.policyRunId());
        }
        RequirementPolicyRun locked = PostgresRequirementPolicyRunStore.toModel(lockedRow);
        if (locked.state() == RequirementPolicyRunState.APPROVED || locked.state() == RequirementPolicyRunState.APPLIED) {
            return idempotentResult(locked, command, approvedBy);
        }
        requireWaitingApproval(locked, command);

        // Lock policy before task everywhere to avoid crossed approval/finalization lock acquisition.
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(command.taskId()));
        requireWaitingRequirementTask(task, command);
        long nextVersion = Math.addExact(command.expectedTaskVersion(), 1L);
        long nextFence = Math.addExact(command.expectedFencingToken(), 1L);

        int taskChanged = taskMapper.advanceStatusWithExpectedVersionFenced(
                PostgresPersistenceSupport.parseId(command.taskId()), command.expectedTaskVersion(),
                command.expectedFencingToken(), RdTaskStatus.WAITING_APPROVAL.name(),
                RdTaskStatus.WAITING_APPROVAL.name(), null, null, null, null,
                PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (taskChanged != 1) {
            throw new IllegalStateException("approval task compare-and-set failed: " + command.taskId());
        }
        appendApprovalEvent(task, command, nowEpochMillis);

        RequirementStageCommand resume = commandFactory.createPendingCommand(
                command.taskId(), nextVersion, nextFence, RESUME_ROLE, RESUME_STAGE,
                safe(task.projectId), safe(task.priority), "", locked.id(), nowEpochMillis);
        if (commandMapper.enqueue(toRow(resume)) != 1) {
            throw new IllegalStateException("approval resume command was not persisted: " + resume.commandId());
        }
        RequirementPolicyRun approved = approvedLedger(
                locked, command, approvedBy, resume.commandId(), nextVersion, nextFence, nowEpochMillis);
        if (policyMapper.compareAndSet(PostgresRequirementPolicyRunStore.toRow(approved),
                RequirementPolicyRunState.WAITING_APPROVAL.name(), locked.ledgerVersion()) != 1) {
            throw new IllegalStateException("approval policy ledger compare-and-set failed: " + command.policyRunId());
        }
        return new RequirementPolicyApprovalResult(approved, resume, nextVersion, nextFence);
    }

    /**
     * Records a decision already evaluated outside this transaction and produces its apply command.
     *
     * <p>The lock order is policy ledger, task, then current stage command. No policy gate is
     * invoked here: it would otherwise run arbitrary evaluation while durable row locks are held.
     * Mock tests verify call order and exception propagation, not physical PostgreSQL rollback.
     */
    @Override
    @Transactional
    public RequirementPolicyEvaluationResult recordEvaluation(
            RecordRequirementPolicyDecisionCommand decision,
            RequirementStageCommand command,
            String leaseOwner,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(command, "command must not be null");
        String owner = require(leaseOwner, "leaseOwner");
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }
        if (!decision.taskId().equals(command.taskId())) {
            throw new IllegalStateException("policy evaluation decision and command target different tasks");
        }

        RequirementPolicyRunRow lockedRow = policyMapper.lockForUpdate(
                PostgresPersistenceSupport.parseId(decision.policyRunId()));
        if (lockedRow == null) {
            throw new IllegalStateException("policy run is missing: " + decision.policyRunId());
        }
        RequirementPolicyRun ledger = PostgresRequirementPolicyRunStore.toModel(lockedRow);
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(command.taskId()));
        RequirementStageCommandRow currentRow = commandMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(command.commandId()));
        if (currentRow == null) {
            throw new IllegalStateException("policy-evaluate command is missing: " + command.commandId());
        }
        RequirementStageCommand current = toCommand(currentRow);

        if (ledger.state() == RequirementPolicyRunState.POLICY_DECIDED) {
            return replayDecidedEvaluation(ledger, task, decision, command, current);
        }
        requirePlanReadyEvaluation(ledger, task, decision, command, current, owner);
        long nextVersion = Math.addExact(decision.expectedTaskVersion(), 1L);
        long nextFence = Math.addExact(decision.expectedFencingToken(), 1L);
        int taskChanged = taskMapper.advanceStatusWithExpectedVersionFenced(
                PostgresPersistenceSupport.parseId(decision.taskId()), decision.expectedTaskVersion(),
                decision.expectedFencingToken(), RdTaskStatus.PLAN_GENERATED.name(), RdTaskStatus.WAITING_POLICY.name(),
                null, null, null, null, PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (taskChanged != 1) {
            throw new IllegalStateException("policy-evaluation task compare-and-set failed: " + decision.taskId());
        }
        appendPolicyDecidedEvent(task, decision.taskId(), decision.policyAction(), nowEpochMillis);

        RequirementStageCommandRow completedRow = commandMapper.complete(
                PostgresPersistenceSupport.parseId(command.commandId()), owner,
                PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (completedRow == null) {
            throw new IllegalStateException("policy-evaluate completion compare-and-set failed: " + command.commandId());
        }
        RequirementStageCommand completed = toCommand(completedRow);
        requireCompletedEvaluation(command, completed, owner);

        RequirementStageCommand requested = commandFactory.createPendingCommand(
                decision.taskId(), nextVersion, nextFence, RESUME_ROLE, POLICY_APPLY_STAGE,
                safe(task.projectId), safe(task.priority), "", ledger.id(), nowEpochMillis);
        if (commandMapper.enqueue(toRow(requested)) != 1) {
            throw new IllegalStateException("policy-apply command was not persisted: " + requested.commandId());
        }
        RequirementStageCommandRow effectiveRow = commandMapper.find(
                PostgresPersistenceSupport.parseId(decision.taskId()), RESUME_ROLE, POLICY_APPLY_STAGE);
        if (effectiveRow == null) {
            throw new IllegalStateException("policy-apply command is missing after enqueue: " + decision.taskId());
        }
        RequirementStageCommand apply = toCommand(effectiveRow);
        requireExactApplyCommand(requested, apply, decision.taskId(), nextVersion, nextFence, ledger.id());

        RequirementPolicyRun decided = decidedLedger(ledger, decision, nextVersion, nextFence, nowEpochMillis);
        if (policyMapper.compareAndSet(PostgresRequirementPolicyRunStore.toRow(decided),
                RequirementPolicyRunState.PLAN_READY.name(), ledger.ledgerVersion()) != 1) {
            throw new IllegalStateException("policy-evaluation ledger compare-and-set failed: " + decision.policyRunId());
        }
        return new RequirementPolicyEvaluationResult(decided, completed, apply, nextVersion, nextFence);
    }

    /**
     * Consumes one pre-evaluated policy decision after the policy evaluator has released all
     * durable locks. Locking the generation before the task and leased apply command prevents a
     * retry from combining a policy decision with another task generation.
     */
    @Override
    @Transactional
    public RequirementPolicyApplyResult consumePolicyApply(
            RequirementStageCommand command, String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(command, "command must not be null");
        String owner = require(leaseOwner, "leaseOwner");
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }
        if (command.policyRunId().isBlank()) {
            throw new IllegalStateException("policy-apply command is missing its policy run reference");
        }

        RequirementPolicyRunRow ledgerRow = policyMapper.lockForUpdate(
                PostgresPersistenceSupport.parseId(command.policyRunId()));
        if (ledgerRow == null) {
            throw new IllegalStateException("policy-apply command is not bound to a decided policy generation: "
                    + command.commandId());
        }
        RequirementPolicyRun ledger = PostgresRequirementPolicyRunStore.toModel(ledgerRow);
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(command.taskId()));
        RequirementStageCommandRow currentRow = commandMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(command.commandId()));
        if (currentRow == null) {
            throw new IllegalStateException("policy-apply command is missing: " + command.commandId());
        }
        RequirementStageCommand current = toCommand(currentRow);

        if (ledger.state() != RequirementPolicyRunState.POLICY_DECIDED) {
            return replayPolicyApply(ledger, task, command, current);
        }
        requireDecidedApply(ledger, task, command, current, owner);
        RequirementPolicyApplyDisposition disposition = dispositionFor(ledger.policyAction());
        long nextVersion = Math.addExact(ledger.boundTaskVersion(), 1L);
        long nextFence = Math.addExact(ledger.boundFencingToken(), 1L);
        RdTaskStatus targetStatus = targetStatusFor(disposition);
        if (taskMapper.advanceStatusWithExpectedVersionFenced(
                PostgresPersistenceSupport.parseId(command.taskId()), ledger.boundTaskVersion(),
                ledger.boundFencingToken(), RdTaskStatus.WAITING_POLICY.name(), targetStatus.name(),
                null, null, null, null, PostgresPersistenceSupport.toDateTime(nowEpochMillis)) != 1) {
            throw new IllegalStateException("policy-apply task compare-and-set failed: " + command.taskId());
        }
        appendPolicyAppliedEvent(task, command.taskId(), targetStatus, ledger.policyAction(), ledger.policyJson(), nowEpochMillis);
        RequirementStageCommandRow completedRow = commandMapper.complete(
                PostgresPersistenceSupport.parseId(command.commandId()), owner,
                PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (completedRow == null) {
            throw new IllegalStateException("policy-apply completion compare-and-set failed: " + command.commandId());
        }
        RequirementStageCommand completed = toCommand(completedRow);
        requireCompletedApply(command, completed, owner);

        RequirementStageCommand next = null;
        if (disposition == RequirementPolicyApplyDisposition.ALLOWED) {
            next = enqueueFirstRole(task, ledger.id(), command.taskId(), nextVersion, nextFence, nowEpochMillis);
        } else {
            requireNoFirstRoleContinuation(command.taskId());
        }
        RequirementPolicyRun consumed = policyApplyLedger(
                ledger, disposition, command.commandId(), nextVersion, nextFence, nowEpochMillis);
        if (policyMapper.compareAndSet(PostgresRequirementPolicyRunStore.toRow(consumed),
                RequirementPolicyRunState.POLICY_DECIDED.name(), ledger.ledgerVersion()) != 1) {
            throw new IllegalStateException("policy-apply ledger compare-and-set failed: " + ledger.id());
        }
        return new RequirementPolicyApplyResult(consumed, completed, disposition, next, nextVersion, nextFence);
    }

    private RequirementPolicyApplyResult replayPolicyApply(
            RequirementPolicyRun ledger, RdTaskRow task, RequirementStageCommand supplied,
            RequirementStageCommand current
    ) {
        RequirementPolicyApplyDisposition disposition = switch (ledger.state()) {
            case APPLIED -> RequirementPolicyApplyDisposition.ALLOWED;
            case WAITING_APPROVAL -> RequirementPolicyApplyDisposition.WAITING_APPROVAL;
            case DENIED -> RequirementPolicyApplyDisposition.DENIED;
            default -> throw new IllegalStateException("policy-apply ledger is not replayable: " + ledger.id());
        };
        requirePolicyApplyReplay(ledger, task, supplied, current, disposition);
        RequirementStageCommand next = null;
        if (disposition == RequirementPolicyApplyDisposition.ALLOWED) {
            next = findExactFirstRoleContinuation(ledger, task);
        } else {
            requireNoFirstRoleContinuation(ledger.taskId());
        }
        return new RequirementPolicyApplyResult(ledger, current, disposition, next,
                ledger.boundTaskVersion(), ledger.boundFencingToken());
    }

    private static void requireDecidedApply(
            RequirementPolicyRun ledger, RdTaskRow task, RequirementStageCommand supplied,
            RequirementStageCommand current, String owner
    ) {
        if (ledger.state() != RequirementPolicyRunState.POLICY_DECIDED
                || !supportedPolicyAction(ledger.policyAction())
                || !ledger.taskId().equals(supplied.taskId())
                || !ledger.id().equals(supplied.policyRunId())
                || ledger.boundTaskVersion() != supplied.taskVersion()
                || ledger.boundFencingToken() != supplied.fencingToken()
                || task == null || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !RdTaskStatus.WAITING_POLICY.name().equals(task.status)
                || number(task.version) != ledger.boundTaskVersion()
                || number(task.fencingToken) != ledger.boundFencingToken()
                || current.status() != RequirementStageCommand.Status.RUNNING
                || !owner.equals(supplied.leaseOwner()) || !owner.equals(current.leaseOwner())
                || !sameImmutableCommandIdentity(supplied, current)
                || !RESUME_ROLE.equals(current.role()) || !POLICY_APPLY_STAGE.equals(current.stage())) {
            throw new IllegalStateException("policy-apply command, task, or ledger identity is stale");
        }
    }

    private static void requireCompletedApply(
            RequirementStageCommand supplied, RequirementStageCommand completed, String owner
    ) {
        if (completed.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutableCommandIdentity(supplied, completed)
                || !RESUME_ROLE.equals(completed.role()) || !POLICY_APPLY_STAGE.equals(completed.stage())
                || !owner.equals(supplied.leaseOwner())) {
            throw new IllegalStateException("policy-apply completion does not match its lease identity: "
                    + supplied.commandId());
        }
    }

    private static void requirePolicyApplyReplay(
            RequirementPolicyRun ledger, RdTaskRow task, RequirementStageCommand supplied,
            RequirementStageCommand current, RequirementPolicyApplyDisposition disposition
    ) {
        RdTaskStatus expectedStatus = targetStatusFor(disposition);
        if (!ledger.taskId().equals(supplied.taskId())
                || !ledger.id().equals(supplied.policyRunId())
                || ledger.sourceTaskVersion() != Math.subtractExact(supplied.taskVersion(), 1L)
                || ledger.sourceFencingToken() != Math.subtractExact(supplied.fencingToken(), 1L)
                || ledger.boundTaskVersion() != Math.addExact(ledger.sourceTaskVersion(), 2L)
                || ledger.boundFencingToken() != Math.addExact(ledger.sourceFencingToken(), 2L)
                || task == null || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !expectedStatus.name().equals(task.status)
                || number(task.version) != ledger.boundTaskVersion()
                || number(task.fencingToken) != ledger.boundFencingToken()
                || current.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutableCommandIdentity(supplied, current)
                || !RESUME_ROLE.equals(current.role()) || !POLICY_APPLY_STAGE.equals(current.stage())
                || (disposition == RequirementPolicyApplyDisposition.ALLOWED
                && (!"ALLOWED".equals(ledger.policyAction())
                || !ledger.consumedByCommandId().equals(supplied.commandId())))
                || (disposition == RequirementPolicyApplyDisposition.WAITING_APPROVAL
                && !"WAITING_APPROVAL".equals(ledger.policyAction()))
                || (disposition == RequirementPolicyApplyDisposition.DENIED
                && (!("NEED_INFO".equals(ledger.policyAction()) || "UNSAFE".equals(ledger.policyAction()))
                || !ledger.consumedByCommandId().equals(supplied.commandId())))) {
            throw new IllegalStateException("policy-apply replay identity conflicts with ledger");
        }
    }

    private RequirementStageCommand enqueueFirstRole(
            RdTaskRow task, String policyRunId, String taskId, long taskVersion, long fencingToken,
            long nowEpochMillis
    ) {
        String role = AgentRole.requirementDeliveryOrder().getFirst().name();
        String stage = "ROLE_EXECUTION:" + role;
        RequirementStageCommand requested = commandFactory.createPendingCommand(
                taskId, taskVersion, fencingToken, role, stage,
                safe(task.projectId), safe(task.priority), "", policyRunId, nowEpochMillis);
        if (commandMapper.enqueue(toRow(requested)) != 1) {
            throw new IllegalStateException("policy-apply first delivery command enqueue conflicted: "
                    + requested.commandId());
        }
        RequirementStageCommandRow effectiveRow = commandMapper.find(
                PostgresPersistenceSupport.parseId(taskId), role, stage);
        if (effectiveRow == null) {
            throw new IllegalStateException("policy-apply first delivery command is missing after enqueue: " + taskId);
        }
        RequirementStageCommand effective = toCommand(effectiveRow);
        requireExactContinuation(requested, effective, taskId, taskVersion, fencingToken, role, stage,
                policyRunId);
        return effective;
    }

    private RequirementStageCommand findExactFirstRoleContinuation(RequirementPolicyRun ledger, RdTaskRow task) {
        String role = AgentRole.requirementDeliveryOrder().getFirst().name();
        String stage = "ROLE_EXECUTION:" + role;
        RequirementStageCommandRow row = commandMapper.find(
                PostgresPersistenceSupport.parseId(ledger.taskId()), role, stage);
        if (row == null) {
            throw new IllegalStateException("applied policy run is missing its first delivery command: " + ledger.id());
        }
        RequirementStageCommand next = toCommand(row);
        requireContinuationIdentity(next, task, ledger.taskId(), ledger.boundTaskVersion(), ledger.boundFencingToken(),
                role, stage, ledger.id(), true);
        return next;
    }

    private void requireNoFirstRoleContinuation(String taskId) {
        String role = AgentRole.requirementDeliveryOrder().getFirst().name();
        String stage = "ROLE_EXECUTION:" + role;
        if (commandMapper.find(PostgresPersistenceSupport.parseId(taskId), role, stage) != null) {
            throw new IllegalStateException("non-allowed policy-apply must not have a first delivery continuation: " + taskId);
        }
    }

    private void appendPolicyAppliedEvent(
            RdTaskRow task, String taskId, RdTaskStatus status, String action, String policyJson,
            long nowEpochMillis
    ) {
        RdTaskStatusEventRow event = new RdTaskStatusEventRow();
        event.id = idGenerator.nextId();
        event.taskId = PostgresPersistenceSupport.parseId(taskId);
        event.status = status.name();
        event.title = safe(task.title);
        event.message = policyEventMessage(action, policyJson);
        event.enteredAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        event.durationMs = 0L;
        event.trigger = RdTaskEventTrigger.SYSTEM.name();
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("policy-apply timeline append failed: " + taskId);
        }
    }

    private static String policyEventMessage(String action, String policyJson) {
        String normalizedAction = require(action, "policyAction");
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(policyJson);
            com.fasterxml.jackson.databind.JsonNode reason = root == null ? null : root.get("reason");
            return reason != null && reason.isTextual() && !reason.textValue().isBlank()
                    ? normalizedAction + ": " + reason.textValue().strip() : normalizedAction;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("stored policy JSON is invalid despite ledger digest validation", invalid);
        }
    }

    private static RequirementPolicyRun policyApplyLedger(
            RequirementPolicyRun current, RequirementPolicyApplyDisposition disposition, String commandId,
            long nextVersion, long nextFence, long nowEpochMillis
    ) {
        RequirementPolicyRunState state = switch (disposition) {
            case ALLOWED -> RequirementPolicyRunState.APPLIED;
            case WAITING_APPROVAL -> RequirementPolicyRunState.WAITING_APPROVAL;
            case DENIED -> RequirementPolicyRunState.DENIED;
        };
        boolean consumed = disposition != RequirementPolicyApplyDisposition.WAITING_APPROVAL;
        return new RequirementPolicyRun(
                current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(),
                current.planJson(), current.planDigest(), current.policyJson(), current.policyDigest(),
                current.policyAction(), state, nextVersion, nextFence,
                null, null, "", "", "", 0L, "", consumed ? commandId : "",
                consumed ? nowEpochMillis : 0L, Math.addExact(current.ledgerVersion(), 1L),
                current.createdAtEpochMillis(), nowEpochMillis);
    }

    private static RequirementPolicyApplyDisposition dispositionFor(String policyAction) {
        return switch (policyAction) {
            case "ALLOWED" -> RequirementPolicyApplyDisposition.ALLOWED;
            case "WAITING_APPROVAL" -> RequirementPolicyApplyDisposition.WAITING_APPROVAL;
            case "NEED_INFO", "UNSAFE" -> RequirementPolicyApplyDisposition.DENIED;
            default -> throw new IllegalStateException("unsupported stored policy action: " + policyAction);
        };
    }

    private static boolean supportedPolicyAction(String policyAction) {
        return "ALLOWED".equals(policyAction) || "WAITING_APPROVAL".equals(policyAction)
                || "NEED_INFO".equals(policyAction) || "UNSAFE".equals(policyAction);
    }

    private static RdTaskStatus targetStatusFor(RequirementPolicyApplyDisposition disposition) {
        return switch (disposition) {
            case ALLOWED -> RdTaskStatus.EXECUTING;
            case WAITING_APPROVAL -> RdTaskStatus.WAITING_APPROVAL;
            case DENIED -> RdTaskStatus.FAILED_NEEDS_HUMAN;
        };
    }

    private RequirementPolicyEvaluationResult replayDecidedEvaluation(
            RequirementPolicyRun ledger, RdTaskRow task, RecordRequirementPolicyDecisionCommand decision,
            RequirementStageCommand supplied, RequirementStageCommand current
    ) {
        requireDecidedReplay(ledger, task, decision, supplied, current);
        RequirementStageCommandRow applyRow = commandMapper.find(
                PostgresPersistenceSupport.parseId(ledger.taskId()), RESUME_ROLE, POLICY_APPLY_STAGE);
        if (applyRow == null) {
            throw new IllegalStateException("decided policy run is missing its apply command: " + ledger.id());
        }
        RequirementStageCommand apply = toCommand(applyRow);
        requireApplyIdentity(apply, task, ledger.taskId(), ledger.boundTaskVersion(), ledger.boundFencingToken(),
                ledger.id(), true);
        return new RequirementPolicyEvaluationResult(ledger, current, apply,
                ledger.boundTaskVersion(), ledger.boundFencingToken());
    }

    private static void requirePlanReadyEvaluation(
            RequirementPolicyRun ledger, RdTaskRow task, RecordRequirementPolicyDecisionCommand decision,
            RequirementStageCommand supplied, RequirementStageCommand current, String leaseOwner
    ) {
        if (ledger.state() != RequirementPolicyRunState.PLAN_READY
                || !ledger.id().equals(decision.policyRunId())
                || !ledger.taskId().equals(decision.taskId())
                || ledger.sourceTaskVersion() != decision.expectedTaskVersion()
                || ledger.sourceFencingToken() != decision.expectedFencingToken()
                || ledger.boundTaskVersion() != decision.expectedTaskVersion()
                || ledger.boundFencingToken() != decision.expectedFencingToken()
                || !ledger.planDigest().equals(decision.planDigest())
                || task == null
                || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !RdTaskStatus.PLAN_GENERATED.name().equals(task.status)
                || number(task.version) != decision.expectedTaskVersion()
                || number(task.fencingToken) != decision.expectedFencingToken()
                || current.status() != RequirementStageCommand.Status.RUNNING
                || !leaseOwner.equals(supplied.leaseOwner())
                || !leaseOwner.equals(current.leaseOwner())
                || !sameImmutableCommandIdentity(supplied, current)
                || !RESUME_ROLE.equals(current.role())
                || !POLICY_EVALUATE_STAGE.equals(current.stage())
                || !decision.policyRunId().equals(current.policyRunId())
                || current.taskVersion() != decision.expectedTaskVersion()
                || current.fencingToken() != decision.expectedFencingToken()) {
            throw new IllegalStateException("policy-evaluation command, task, or ledger identity is stale");
        }
    }

    private static void requirePreparationCommand(
            RequirementPolicyEvaluationProposal proposal, RequirementStageCommand command
    ) {
        if (!command.commandId().equals(command.policyRunId())
                || !proposal.taskId().equals(command.taskId())
                || proposal.taskVersion() != command.taskVersion()
                || proposal.fencingToken() != command.fencingToken()
                || command.status() != RequirementStageCommand.Status.PENDING
                || !command.leaseOwner().isBlank()
                || command.leaseUntilEpochMillis() != 0L
                || !RESUME_ROLE.equals(command.role())
                || !POLICY_EVALUATE_STAGE.equals(command.stage())) {
            throw new IllegalStateException("policy-evaluate preparation command identity is invalid: "
                    + command.commandId());
        }
    }

    private static void requirePlanGeneratedTask(RdTaskRow task, RequirementPolicyEvaluationProposal proposal) {
        if (task == null || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !RdTaskStatus.PLAN_GENERATED.name().equals(task.status)
                || number(task.version) != proposal.taskVersion()
                || number(task.fencingToken) != proposal.fencingToken()) {
            throw new IllegalStateException("policy preparation task snapshot is stale or not PLAN_GENERATED: "
                    + proposal.taskId());
        }
    }

    private static void requireCompletedEvaluation(
            RequirementStageCommand supplied, RequirementStageCommand completed, String leaseOwner
    ) {
        if (completed.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutableCommandIdentity(supplied, completed)
                || !RESUME_ROLE.equals(completed.role())
                || !POLICY_EVALUATE_STAGE.equals(completed.stage())
                || !leaseOwner.equals(supplied.leaseOwner())) {
            throw new IllegalStateException("policy-evaluate completion does not match its lease identity: "
                    + supplied.commandId());
        }
    }

    private static void requireDecidedReplay(
            RequirementPolicyRun ledger, RdTaskRow task, RecordRequirementPolicyDecisionCommand decision,
            RequirementStageCommand supplied, RequirementStageCommand current
    ) {
        if (ledger.state() != RequirementPolicyRunState.POLICY_DECIDED
                || !ledger.id().equals(decision.policyRunId())
                || !ledger.taskId().equals(decision.taskId())
                || ledger.sourceTaskVersion() != decision.expectedTaskVersion()
                || ledger.sourceFencingToken() != decision.expectedFencingToken()
                || ledger.boundTaskVersion() != Math.addExact(ledger.sourceTaskVersion(), 1L)
                || ledger.boundFencingToken() != Math.addExact(ledger.sourceFencingToken(), 1L)
                || !ledger.planDigest().equals(decision.planDigest())
                || !RequirementPolicyRun.canonicalizeJson(ledger.policyJson()).equals(decision.policyJson())
                || !ledger.policyDigest().equals(decision.policyDigest())
                || !ledger.policyAction().equals(decision.policyAction())
                || task == null
                || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !RdTaskStatus.WAITING_POLICY.name().equals(task.status)
                || number(task.version) != ledger.boundTaskVersion()
                || number(task.fencingToken) != ledger.boundFencingToken()
                || current.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutableCommandIdentity(supplied, current)
                || !RESUME_ROLE.equals(current.role())
                || !POLICY_EVALUATE_STAGE.equals(current.stage())
                || !ledger.id().equals(current.policyRunId())
                || current.taskVersion() != ledger.sourceTaskVersion()
                || current.fencingToken() != ledger.sourceFencingToken()) {
            throw new IllegalStateException("decided policy-evaluation replay identity conflicts with ledger");
        }
    }

    private static void requireExactApplyCommand(
            RequirementStageCommand requested, RequirementStageCommand effective, String taskId,
            long taskVersion, long fencingToken, String policyRunId
    ) {
        if (!requested.equals(effective)) {
            throw new IllegalStateException("policy-apply enqueue returned a conflicting effective command");
        }
        requireApplyIdentity(effective, null, taskId, taskVersion, fencingToken, policyRunId, false);
    }

    private static void requireApplyIdentity(
            RequirementStageCommand apply, RdTaskRow task, String taskId, long taskVersion, long fencingToken,
            String policyRunId, boolean requirePendingSchedulingIdentity
    ) {
        if (!taskId.equals(apply.taskId())
                || apply.taskVersion() != taskVersion
                || apply.fencingToken() != fencingToken
                || apply.fencingToken() <= 0L
                || !RESUME_ROLE.equals(apply.role())
                || !POLICY_APPLY_STAGE.equals(apply.stage())
                || !policyRunId.equals(apply.policyRunId())
                || (requirePendingSchedulingIdentity && (apply.status() != RequirementStageCommand.Status.PENDING
                || apply.attemptNo() != 0 || !apply.leaseOwner().isBlank()
                || apply.leaseUntilEpochMillis() != 0L || apply.deadlineEpochMillis() <= 0L
                || apply.providerId().isBlank() || task == null
                || !normalizeProject(task.projectId).equals(apply.projectId())
                || priorityRank(task.priority) != apply.priorityRank()
                || !apply.resourceRequirements().equals(
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(apply.role(), apply.stage()))
                || !apply.resourceRequirements().contains(apply.resourceClass())))) {
            throw new IllegalStateException("policy-apply command identity conflicts with decided ledger");
        }
    }

    private void appendPolicyDecidedEvent(
            RdTaskRow task, String taskId, String policyAction, long nowEpochMillis
    ) {
        RdTaskStatusEventRow event = new RdTaskStatusEventRow();
        event.id = idGenerator.nextId();
        event.taskId = PostgresPersistenceSupport.parseId(taskId);
        event.status = RdTaskStatus.WAITING_POLICY.name();
        event.title = safe(task.title);
        event.message = safe(policyAction);
        event.enteredAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        event.durationMs = 0L;
        event.trigger = RdTaskEventTrigger.SYSTEM.name();
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("policy-evaluation timeline append failed: " + taskId);
        }
    }

    private static RequirementPolicyRun decidedLedger(
            RequirementPolicyRun current, RecordRequirementPolicyDecisionCommand decision,
            long nextVersion, long nextFence, long nowEpochMillis
    ) {
        return new RequirementPolicyRun(
                current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(),
                current.planJson(), current.planDigest(), decision.policyJson(), decision.policyDigest(),
                decision.policyAction(), RequirementPolicyRunState.POLICY_DECIDED, nextVersion, nextFence,
                null, null, "", "", "", 0L, "", "", 0L,
                Math.addExact(current.ledgerVersion(), 1L), current.createdAtEpochMillis(), nowEpochMillis);
    }

    /**
     * Consumes a leased approval-resume command in one ledger-to-task-to-command transaction.
     *
     * <p>Mock tests cover ordering, argument identity, and exception propagation only; they do
     * not prove real PostgreSQL rollback, which remains a dedicated integration-smoke concern.
     */
    @Override
    @Transactional
    public RequirementPolicyResumeResult consumeApproval(
            RequirementStageCommand command, String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(command, "command must not be null");
        String owner = require(leaseOwner, "leaseOwner");
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }

        // This global order prevents a resume consumer from crossing the approval producer locks.
        if (command.policyRunId().isBlank()) {
            throw new IllegalStateException("approval resume command is missing its policy run reference");
        }
        RequirementPolicyRunRow lockedRow = policyMapper.lockForUpdate(
                PostgresPersistenceSupport.parseId(command.policyRunId()));
        if (lockedRow == null) {
            throw new IllegalStateException("approval resume is not bound to a policy run: " + command.commandId());
        }
        RequirementPolicyRun ledger = PostgresRequirementPolicyRunStore.toModel(lockedRow);
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(command.taskId()));
        RequirementStageCommandRow currentRow = commandMapper.lockByIdForUpdate(
                PostgresPersistenceSupport.parseId(command.commandId()));
        if (currentRow == null) {
            throw new IllegalStateException("approval resume command is missing: " + command.commandId());
        }
        RequirementStageCommand current = toCommand(currentRow);

        if (ledger.state() == RequirementPolicyRunState.APPLIED) {
            return replayApplied(ledger, task, command, current);
        }
        requireApprovedResume(ledger, task, command, current, owner);
        long nextVersion = Math.addExact(ledger.boundTaskVersion(), 1L);
        long nextFence = Math.addExact(ledger.boundFencingToken(), 1L);
        int taskChanged = taskMapper.advanceStatusWithExpectedVersionFenced(
                PostgresPersistenceSupport.parseId(command.taskId()), ledger.boundTaskVersion(),
                ledger.boundFencingToken(), RdTaskStatus.WAITING_APPROVAL.name(), RdTaskStatus.EXECUTING.name(),
                null, null, null, null, PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (taskChanged != 1) {
            throw new IllegalStateException("approval-resume task compare-and-set failed: " + command.taskId());
        }
        appendExecutingEvent(task, command.taskId(), nowEpochMillis);

        RequirementStageCommandRow completedRow = commandMapper.complete(
                PostgresPersistenceSupport.parseId(command.commandId()), owner,
                PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (completedRow == null) {
            throw new IllegalStateException("approval-resume completion compare-and-set failed: " + command.commandId());
        }
        RequirementStageCommand completed = toCommand(completedRow);
        requireCompletedResume(command, completed, owner);

        String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
        String firstStage = "ROLE_EXECUTION:" + firstRole;
        RequirementStageCommand requested = commandFactory.createPendingCommand(
                command.taskId(), nextVersion, nextFence, firstRole, firstStage,
                safe(task.projectId), safe(task.priority), "", ledger.id(), nowEpochMillis);
        if (commandMapper.enqueue(toRow(requested)) != 1) {
            throw new IllegalStateException("first delivery command enqueue conflicted: " + requested.commandId());
        }
        RequirementStageCommandRow effectiveRow = commandMapper.find(
                PostgresPersistenceSupport.parseId(command.taskId()), firstRole, firstStage);
        if (effectiveRow == null) {
            throw new IllegalStateException("first delivery command is missing after enqueue: " + command.taskId());
        }
        RequirementStageCommand next = toCommand(effectiveRow);
        requireExactContinuation(requested, next, command.taskId(), nextVersion, nextFence, firstRole,
                firstStage, ledger.id());

        RequirementPolicyRun applied = appliedLedger(ledger, command.commandId(), nextVersion, nextFence, nowEpochMillis);
        if (policyMapper.compareAndSet(PostgresRequirementPolicyRunStore.toRow(applied),
                RequirementPolicyRunState.APPROVED.name(), ledger.ledgerVersion()) != 1) {
            throw new IllegalStateException("approval-resume policy ledger compare-and-set failed: " + ledger.id());
        }
        return new RequirementPolicyResumeResult(applied, completed, next, nextVersion, nextFence);
    }

    private RequirementPolicyResumeResult replayApplied(
            RequirementPolicyRun ledger, RdTaskRow task, RequirementStageCommand supplied,
            RequirementStageCommand current
    ) {
        requireAppliedReplay(ledger, task, supplied, current);
        String firstRole = AgentRole.requirementDeliveryOrder().getFirst().name();
        String firstStage = "ROLE_EXECUTION:" + firstRole;
        RequirementStageCommandRow continuationRow = commandMapper.find(
                PostgresPersistenceSupport.parseId(ledger.taskId()), firstRole, firstStage);
        if (continuationRow == null) {
            throw new IllegalStateException("applied approval-resume is missing its first delivery command: " + ledger.id());
        }
        RequirementStageCommand next = toCommand(continuationRow);
        requireContinuationIdentity(next, task, ledger.taskId(), ledger.boundTaskVersion(), ledger.boundFencingToken(),
                firstRole, firstStage, ledger.id(), true);
        return new RequirementPolicyResumeResult(ledger, current, next,
                ledger.boundTaskVersion(), ledger.boundFencingToken());
    }

    private static void requireApprovedResume(
            RequirementPolicyRun ledger, RdTaskRow task, RequirementStageCommand supplied,
            RequirementStageCommand current, String leaseOwner
    ) {
        if (ledger.state() != RequirementPolicyRunState.APPROVED
                || !"WAITING_APPROVAL".equals(ledger.policyAction())
                || !approvalBoundPairIsExact(ledger, 1L)
                || ledger.boundTaskVersion() < 0L
                || ledger.boundFencingToken() <= 0L
                || !ledger.taskId().equals(supplied.taskId())
                || !ledger.approvalResumeCommandId().equals(supplied.commandId())
                || !ledger.id().equals(supplied.policyRunId())
                || task == null
                || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !RdTaskStatus.WAITING_APPROVAL.name().equals(task.status)
                || number(task.version) != ledger.boundTaskVersion()
                || number(task.fencingToken) != ledger.boundFencingToken()
                || current.taskVersion() != ledger.boundTaskVersion()
                || current.fencingToken() != ledger.boundFencingToken()
                || current.status() != RequirementStageCommand.Status.RUNNING
                || !leaseOwner.equals(current.leaseOwner())
                || !leaseOwner.equals(supplied.leaseOwner())
                || !sameImmutableCommandIdentity(supplied, current)
                || !RESUME_ROLE.equals(current.role())
                || !RESUME_STAGE.equals(current.stage())) {
            throw new IllegalStateException("approval-resume command, task, or ledger identity is stale");
        }
    }

    private static void requireCompletedResume(
            RequirementStageCommand supplied, RequirementStageCommand completed, String leaseOwner
    ) {
        if (completed.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutableCommandIdentity(supplied, completed)
                || !RESUME_ROLE.equals(completed.role())
                || !RESUME_STAGE.equals(completed.stage())
                || !leaseOwner.equals(supplied.leaseOwner())) {
            throw new IllegalStateException("approval-resume completion does not match its lease identity: "
                    + supplied.commandId());
        }
    }

    private static void requireAppliedReplay(
            RequirementPolicyRun ledger, RdTaskRow task, RequirementStageCommand supplied,
            RequirementStageCommand current
    ) {
        if (ledger.state() != RequirementPolicyRunState.APPLIED
                || !"WAITING_APPROVAL".equals(ledger.policyAction())
                || !approvalBoundPairIsExact(ledger, 2L)
                || !ledger.taskId().equals(supplied.taskId())
                || !ledger.approvalResumeCommandId().equals(supplied.commandId())
                || !ledger.id().equals(supplied.policyRunId())
                || !ledger.consumedByCommandId().equals(supplied.commandId())
                || task == null
                || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !ledger.taskId().equals(PostgresPersistenceSupport.idString(task.id))
                || !RdTaskStatus.EXECUTING.name().equals(task.status)
                || number(task.version) != ledger.boundTaskVersion()
                || number(task.fencingToken) != ledger.boundFencingToken()
                || current.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutableCommandIdentity(supplied, current)
                || !RESUME_ROLE.equals(current.role())
                || !RESUME_STAGE.equals(current.stage())) {
            throw new IllegalStateException("applied approval-resume replay identity conflicts with ledger");
        }
    }

    private static boolean sameImmutableCommandIdentity(
            RequirementStageCommand expected, RequirementStageCommand actual
    ) {
        return expected.commandId().equals(actual.commandId())
                && expected.taskId().equals(actual.taskId())
                && expected.taskVersion() == actual.taskVersion()
                && expected.fencingToken() == actual.fencingToken()
                && expected.role().equals(actual.role())
                && expected.stage().equals(actual.stage())
                && expected.attemptNo() == actual.attemptNo()
                && expected.maxAttempts() == actual.maxAttempts()
                && expected.deadlineEpochMillis() == actual.deadlineEpochMillis()
                && expected.resourceClass() == actual.resourceClass()
                && expected.resourceRequirements().equals(actual.resourceRequirements())
                && expected.projectId().equals(actual.projectId())
                && expected.providerId().equals(actual.providerId())
                && expected.priorityRank() == actual.priorityRank()
                && expected.policyRunId().equals(actual.policyRunId())
                && expected.createdAtEpochMillis() == actual.createdAtEpochMillis();
    }

    private static void requireExactContinuation(
            RequirementStageCommand requested, RequirementStageCommand effective, String taskId,
            long taskVersion, long fencingToken, String role, String stage, String policyRunId
    ) {
        if (!requested.equals(effective)) {
            throw new IllegalStateException("first delivery command enqueue returned a conflicting effective command");
        }
        requireContinuationIdentity(effective, null, taskId, taskVersion, fencingToken, role, stage,
                policyRunId, false);
    }

    private static void requireContinuationIdentity(
            RequirementStageCommand command, RdTaskRow task, String taskId, long taskVersion, long fencingToken,
            String role, String stage, String policyRunId, boolean requireDurableSchedulingIdentity
    ) {
        if (!taskId.equals(command.taskId())
                || command.taskVersion() != taskVersion
                || command.fencingToken() != fencingToken
                || command.fencingToken() <= 0L
                || !role.equals(command.role())
                || !stage.equals(command.stage())
                || policyRunId == null || policyRunId.isBlank()
                || !policyRunId.equals(command.policyRunId())) {
            throw new IllegalStateException("first delivery command identity conflicts with applied task generation");
        }
        if (requireDurableSchedulingIdentity
                && (command.status() != RequirementStageCommand.Status.PENDING
                || command.attemptNo() != 0
                || !command.leaseOwner().isBlank()
                || command.leaseUntilEpochMillis() != 0L
                || command.deadlineEpochMillis() <= 0L
                || !command.resourceRequirements().equals(FairRequirementDeliveryClaimPlanner.classifyStageRequirements(role, stage))
                || !command.resourceRequirements().contains(command.resourceClass())
                || !normalizeProject(task.projectId).equals(command.projectId())
                || priorityRank(task.priority) != command.priorityRank()
                || command.providerId().isBlank())) {
            throw new IllegalStateException("applied approval-resume continuation scheduling identity conflicts with task");
        }
    }

    private static String normalizeProject(String projectId) {
        return projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
    }

    private static int priorityRank(String priority) {
        String normalized = safe(priority).toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("P")) {
            try {
                return Math.max(0, Integer.parseInt(normalized.substring(1)));
            } catch (NumberFormatException ignored) {
                // Fall through to RequirementStageCommand's conservative default.
            }
        }
        return 2;
    }

    private void appendExecutingEvent(RdTaskRow task, String taskId, long nowEpochMillis) {
        RdTaskStatusEventRow event = new RdTaskStatusEventRow();
        event.id = idGenerator.nextId();
        event.taskId = PostgresPersistenceSupport.parseId(taskId);
        event.status = RdTaskStatus.EXECUTING.name();
        event.title = safe(task.title);
        event.message = "";
        event.enteredAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        event.durationMs = 0L;
        event.trigger = RdTaskEventTrigger.SYSTEM.name();
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("approval-resume executing timeline append failed: " + taskId);
        }
    }

    private static RequirementPolicyRun appliedLedger(
            RequirementPolicyRun current, String consumedCommandId, long nextVersion, long nextFence,
            long nowEpochMillis
    ) {
        return new RequirementPolicyRun(
                current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(),
                current.planJson(), current.planDigest(), current.policyJson(), current.policyDigest(),
                current.policyAction(), RequirementPolicyRunState.APPLIED, nextVersion, nextFence,
                current.approvalExpectedTaskVersion(), current.approvalExpectedFencingToken(),
                current.approvalRequestId(), current.approvedBy(), current.note(), current.approvedAtEpochMillis(),
                current.approvalResumeCommandId(), consumedCommandId, nowEpochMillis,
                Math.addExact(current.ledgerVersion(), 1L), current.createdAtEpochMillis(), nowEpochMillis);
    }

    private RequirementPolicyApprovalResult idempotentResult(
            RequirementPolicyRun ledger, ApproveRequirementPolicyCommand command, String actor
    ) {
        if (!ledger.taskId().equals(command.taskId())
                || !"WAITING_APPROVAL".equals(ledger.policyAction())
                || !ledger.approvalRequestId().equals(command.approvalRequestId())
                || !ledger.approvedBy().equals(actor)
                || !ledger.note().equals(command.note())
                || !ledger.planDigest().equals(command.planDigest())
                || !ledger.policyDigest().equals(command.policyDigest())
                || !Objects.equals(ledger.approvalExpectedTaskVersion(), command.expectedTaskVersion())
                || !Objects.equals(ledger.approvalExpectedFencingToken(), command.expectedFencingToken())) {
            throw new IllegalStateException("approval idempotency payload conflicts with policy run: " + command.policyRunId());
        }
        if (ledger.approvalResumeCommandId().isBlank()) {
            throw new IllegalStateException("approved policy run is missing its bound resume command id: " + command.policyRunId());
        }
        RequirementStageCommandRow commandRow = commandMapper.findById(
                PostgresPersistenceSupport.parseId(ledger.approvalResumeCommandId()));
        if (commandRow == null) {
            throw new IllegalStateException("approved policy run is missing its resume command: " + command.policyRunId());
        }
        RequirementStageCommand resume = toCommand(commandRow);
        long approvalProducedVersion = Math.addExact(command.expectedTaskVersion(), 1L);
        long approvalProducedFence = Math.addExact(command.expectedFencingToken(), 1L);
        if (!resume.commandId().equals(ledger.approvalResumeCommandId())
                || !resume.taskId().equals(command.taskId())
                || !ledger.id().equals(resume.policyRunId())
                || resume.taskVersion() != approvalProducedVersion
                || resume.fencingToken() != approvalProducedFence
                || !RESUME_ROLE.equals(resume.role())
                || !RESUME_STAGE.equals(resume.stage())) {
            throw new IllegalStateException("approved policy run resume command does not match its ledger: " + command.policyRunId());
        }
        return new RequirementPolicyApprovalResult(ledger, resume, approvalProducedVersion, approvalProducedFence);
    }

    private static void requireWaitingApproval(RequirementPolicyRun ledger, ApproveRequirementPolicyCommand command) {
        if (ledger.state() != RequirementPolicyRunState.WAITING_APPROVAL
                || !ledger.taskId().equals(command.taskId())
                || !ledger.planDigest().equals(command.planDigest())
                || !ledger.policyDigest().equals(command.policyDigest())
                || ledger.boundTaskVersion() != command.expectedTaskVersion()
                || ledger.boundFencingToken() != command.expectedFencingToken()) {
            throw new IllegalStateException("approval request does not match waiting policy ledger: " + command.policyRunId());
        }
    }

    private static void requireWaitingRequirementTask(RdTaskRow task, ApproveRequirementPolicyCommand command) {
        if (task == null || !RdRequirementTask.TASK_TYPE.equals(task.taskType)
                || !RdTaskStatus.WAITING_APPROVAL.name().equals(task.status)
                || number(task.version) != command.expectedTaskVersion()
                || number(task.fencingToken) != command.expectedFencingToken()
                || command.expectedFencingToken() <= 0L) {
            throw new IllegalStateException("approval task snapshot is stale or not waiting: " + command.taskId());
        }
    }

    private void appendApprovalEvent(RdTaskRow task, ApproveRequirementPolicyCommand command, long nowEpochMillis) {
        RdTaskStatusEventRow event = new RdTaskStatusEventRow();
        event.id = idGenerator.nextId();
        event.taskId = PostgresPersistenceSupport.parseId(command.taskId());
        event.status = "APPROVED";
        event.title = safe(task.title);
        event.message = command.note();
        event.enteredAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        event.durationMs = 0L;
        event.trigger = RdTaskEventTrigger.MANUAL.name();
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("approval timeline append failed: " + command.taskId());
        }
    }

    private static RequirementPolicyRun approvedLedger(
            RequirementPolicyRun current, ApproveRequirementPolicyCommand command, String actor,
            String approvalResumeCommandId, long nextVersion, long nextFence, long nowEpochMillis
    ) {
        return new RequirementPolicyRun(
                current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(),
                current.planJson(), current.planDigest(), current.policyJson(), current.policyDigest(),
                current.policyAction(), RequirementPolicyRunState.APPROVED, nextVersion, nextFence,
                command.expectedTaskVersion(), command.expectedFencingToken(), command.approvalRequestId(), actor,
                command.note(), nowEpochMillis, approvalResumeCommandId, "", 0L, Math.addExact(current.ledgerVersion(), 1L),
                current.createdAtEpochMillis(), nowEpochMillis);
    }

    private static RequirementStageCommandRow toRow(RequirementStageCommand command) {
        RequirementStageCommandRow row = new RequirementStageCommandRow();
        row.id = PostgresPersistenceSupport.parseId(command.commandId());
        row.taskId = PostgresPersistenceSupport.parseId(command.taskId());
        row.taskVersion = command.taskVersion(); row.fencingToken = command.fencingToken();
        row.role = command.role(); row.stage = command.stage();
        row.policyRunId = command.policyRunId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(command.policyRunId());
        row.retryCheckpointId = command.retryCheckpointId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(command.retryCheckpointId());
        row.businessGeneration = command.businessGeneration();
        row.targetRetryBindingId = command.targetRetryBindingId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(command.targetRetryBindingId());
        row.attemptNo = command.attemptNo(); row.maxAttempts = command.maxAttempts();
        row.deadlineAt = PostgresPersistenceSupport.toDateTime(command.deadlineEpochMillis());
        row.resourceClass = command.resourceClass().name(); row.resourceRequirements = command.encodedResourceRequirements();
        row.projectId = command.projectId(); row.providerId = command.providerId(); row.priorityRank = command.priorityRank();
        row.status = command.status().name(); row.leaseOwner = command.leaseOwner();
        row.leaseUntil = command.leaseUntilEpochMillis() == 0L ? null : PostgresPersistenceSupport.toDateTime(command.leaseUntilEpochMillis());
        row.nextVisibleAt = PostgresPersistenceSupport.toDateTime(command.nextVisibleAtEpochMillis()); row.lastError = command.lastError();
        row.createdAt = PostgresPersistenceSupport.toDateTime(command.createdAtEpochMillis()); row.updatedAt = PostgresPersistenceSupport.toDateTime(command.updatedAtEpochMillis());
        return row;
    }

    private static RequirementStageCommand toCommand(RequirementStageCommandRow row) {
        ScheduleResourceClass resource = ScheduleResourceClass.valueOf(require(row.resourceClass, "resourceClass"));
        return new RequirementStageCommand(
                PostgresPersistenceSupport.idString(row.id), PostgresPersistenceSupport.idString(row.taskId), number(row.taskVersion), number(row.fencingToken),
                safe(row.role), require(row.stage, "stage"), row.attemptNo == null ? 0 : row.attemptNo, row.maxAttempts == null ? 1 : row.maxAttempts,
                PostgresPersistenceSupport.toEpochMillis(row.deadlineAt), resource,
                RequirementStageCommand.decodeResourceRequirements(row.resourceRequirements, resource), safe(row.projectId), safe(row.providerId),
                row.priorityRank == null ? 0 : row.priorityRank, RequirementStageCommand.Status.valueOf(require(row.status, "status")),
                safe(row.leaseOwner), PostgresPersistenceSupport.toEpochMillis(row.leaseUntil), PostgresPersistenceSupport.toEpochMillis(row.nextVisibleAt),
                safe(row.lastError), PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.policyRunId == null ? "" : PostgresPersistenceSupport.idString(row.policyRunId),
                row.retryCheckpointId == null ? "" : PostgresPersistenceSupport.idString(row.retryCheckpointId),
                row.businessGeneration == null ? 0L : row.businessGeneration,
                row.targetRetryBindingId == null ? "" : PostgresPersistenceSupport.idString(row.targetRetryBindingId));
    }

    private static long number(Long value) { return value == null ? 0L : value; }
    private static boolean approvalBoundPairIsExact(RequirementPolicyRun ledger, long increments) {
        return ledger.approvalExpectedTaskVersion() != null
                && ledger.approvalExpectedFencingToken() != null
                && ledger.boundTaskVersion() == Math.addExact(ledger.approvalExpectedTaskVersion(), increments)
                && ledger.boundFencingToken() == Math.addExact(ledger.approvalExpectedFencingToken(), increments)
                && ledger.boundFencingToken() > 0L;
    }
    private static String safe(String value) { return value == null ? "" : value.strip(); }
    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
