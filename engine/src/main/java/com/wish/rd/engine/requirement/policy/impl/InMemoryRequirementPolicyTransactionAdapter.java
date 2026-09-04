package com.wish.rd.engine.requirement.policy.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.audit.AuditedTaskStatePolicyBootstrap;
import com.wish.rd.engine.requirement.audit.AuditedTaskStateStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.RequirementPolicyTransactionPort;
import com.wish.rd.engine.requirement.policy.model.ApproveRequirementPolicyCommand;
import com.wish.rd.engine.requirement.policy.model.RecordRequirementPolicyDecisionCommand;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyDisposition;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApplyResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyApprovalResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationProposal;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyEvaluationPreparationResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyResumeResult;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRetryContext;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import java.util.Objects;

/**
 * Single-runtime implementation of the policy transaction port for explicit memory mode.
 *
 * <p>Every mutation is synchronized and validates the ledger, task, leased command and any
 * continuation before writing. It intentionally supplies no rollback guarantee across stores;
 * PostgreSQL remains the production transaction proof.
 */
public final class InMemoryRequirementPolicyTransactionAdapter implements RequirementPolicyTransactionPort {
    private static final String DELIVERY = "REQUIREMENT_DELIVERY";
    private static final String EVALUATE = "POLICY_EVALUATE";
    private static final String APPLY = "POLICY_APPLY";
    private static final String RESUME = "APPROVAL_RESUME";
    private static final long COMMAND_DEADLINE_MILLIS = 300_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RequirementPolicyRunStore policyRuns;
    private final RdTaskStore tasks;
    private final RdTaskStatusEventStore events;
    private final RequirementStageCommandStore commands;
    private final SnowflakeIdGenerator ids;
    private final AuditedTaskStateStore auditedTaskStateStore;

    /** Creates the local transaction adapter from the same stores used by memory-mode delivery. */
    public InMemoryRequirementPolicyTransactionAdapter(
            RequirementPolicyRunStore policyRuns,
            RdTaskStore tasks,
            RdTaskStatusEventStore events,
            RequirementStageCommandStore commands,
            SnowflakeIdGenerator ids
    ) {
        this(policyRuns, tasks, events, commands, ids, null);
    }

    /**
     * Creates the local transaction adapter with audited-state initialization.
     *
     * @param auditedTaskStateStore Host audited-state store; required when applying ALLOWED/resume
     */
    public InMemoryRequirementPolicyTransactionAdapter(
            RequirementPolicyRunStore policyRuns,
            RdTaskStore tasks,
            RdTaskStatusEventStore events,
            RequirementStageCommandStore commands,
            SnowflakeIdGenerator ids,
            AuditedTaskStateStore auditedTaskStateStore
    ) {
        this.policyRuns = Objects.requireNonNull(policyRuns, "policyRuns must not be null");
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.auditedTaskStateStore = auditedTaskStateStore;
    }

    /** CAS-terminalizes only an eligible active policy source for one exact POLICY retry lineage. */
    @Override
    public synchronized RequirementPolicyRun supersedeForPolicyRetry(
            RequirementPolicyRetryContext context, long expectedLedgerVersion, long nowEpochMillis
    ) {
        if (context == null || context.isEmpty()) {
            throw new IllegalArgumentException("policy retry context must be complete");
        }
        requireClock(nowEpochMillis);
        RequirementPolicyRun source = requireRun(context.sourcePolicyRunId());
        if (!source.planDigest().equals(context.sourcePlanDigest()) || !source.state().canBeSuperseded()) {
            throw new IllegalStateException("policy retry source cannot be superseded: " + source.id());
        }
        return policyRuns.supersedeForPolicyRetry(context, expectedLedgerVersion, nowEpochMillis);
    }

    /** Creates or replays the fenced PLAN_READY/evaluate-command pair as one memory-mode operation. */
    @Override
    public synchronized RequirementPolicyEvaluationPreparationResult prepareEvaluation(
            RequirementPolicyEvaluationProposal proposal,
            RequirementStageCommand supplied,
            long nowEpochMillis
    ) {
        requireNormalContext(proposal.retryContext());
        return prepareEvaluation(proposal, supplied, RequirementPolicyRetryContext.empty(), nowEpochMillis);
    }

    @Override
    public synchronized RequirementPolicyEvaluationPreparationResult prepareEvaluation(
            RequirementPolicyEvaluationProposal proposal,
            RequirementStageCommand supplied,
            RequirementPolicyRetryContext retryContext,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(supplied, "policyEvaluateCommand must not be null");
        requireSameContext(retryContext, proposal.retryContext(), "proposal");
        retryContext.requireControlCommandIdentity(supplied.retryCheckpointId(), supplied.businessGeneration(),
                supplied.targetRetryBindingId());
        requireClock(nowEpochMillis);
        RdRequirementTask task = requireTask(proposal.taskId());
        if (task.status() != RdTaskStatus.PLAN_GENERATED
                || task.version() != proposal.taskVersion()
                || task.fencingToken() != proposal.fencingToken()
                || !supplied.commandId().equals(supplied.policyRunId())
                || !supplied.taskId().equals(proposal.taskId())
                || supplied.taskVersion() != proposal.taskVersion()
                || supplied.fencingToken() != proposal.fencingToken()
                || supplied.status() != RequirementStageCommand.Status.PENDING
                || !supplied.leaseOwner().isBlank()
                || supplied.leaseUntilEpochMillis() != 0L
                || !DELIVERY.equals(supplied.role()) || !EVALUATE.equals(supplied.stage())) {
            throw new IllegalStateException("policy-evaluate preparation identity is stale");
        }
        RequirementPolicyRun requested = new RequirementPolicyRun(
                supplied.policyRunId(), proposal.taskId(), proposal.taskVersion(), proposal.fencingToken(),
                proposal.planJson(), proposal.planDigest(), "", "", "", RequirementPolicyRunState.PLAN_READY,
                proposal.taskVersion(), proposal.fencingToken(), null, null, "", "", "", 0L,
                "", "", 0L, 0L, nowEpochMillis, nowEpochMillis);
        RequirementPolicyRun existingRun = policyRuns.findById(requested.id()).orElse(null);
        RequirementStageCommand existingCommand = commands.findById(supplied.commandId()).orElse(null);
        if ((existingRun == null) != (existingCommand == null)) {
            throw new IllegalStateException("policy-evaluate preparation is incomplete: " + supplied.commandId());
        }
        if (existingRun != null) {
            if (!existingRun.equals(requested) || !existingCommand.equals(supplied)) {
                throw new IllegalStateException("policy-evaluate preparation conflicts: " + supplied.commandId());
            }
            return new RequirementPolicyEvaluationPreparationResult(existingRun, existingCommand, retryContext);
        }
        // Every potentially conflicting read and all derived values are complete before either
        // store is written. In-memory stores are non-throwing after their preflight; PostgreSQL
        // supplies the cross-store rollback proof for production mode.
        RequirementPolicyRun stored = policyRuns.createOrGet(requested);
        RequirementStageCommand storedCommand = commands.enqueue(supplied);
        return new RequirementPolicyEvaluationPreparationResult(stored, storedCommand, retryContext);
    }

    /** Records an already-evaluated decision and creates the exact policy-apply command. */
    @Override
    public synchronized RequirementPolicyEvaluationResult recordEvaluation(
            RecordRequirementPolicyDecisionCommand decision, RequirementStageCommand supplied,
            String leaseOwner, long nowEpochMillis
    ) {
        requireNormalContext(decision.retryContext());
        return recordEvaluation(decision, supplied, RequirementPolicyRetryContext.empty(), leaseOwner, nowEpochMillis);
    }

    @Override
    public synchronized RequirementPolicyEvaluationResult recordEvaluation(
            RecordRequirementPolicyDecisionCommand decision, RequirementStageCommand supplied,
            RequirementPolicyRetryContext retryContext, String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(supplied, "command must not be null");
        requireSameContext(retryContext, decision.retryContext(), "decision");
        retryContext.requireControlCommandIdentity(supplied.retryCheckpointId(), supplied.businessGeneration(),
                supplied.targetRetryBindingId());
        String owner = require(leaseOwner, "leaseOwner");
        requireClock(nowEpochMillis);
        RequirementPolicyRun ledger = requireRun(decision.policyRunId());
        RdRequirementTask task = requireTask(decision.taskId());
        RequirementStageCommand current = requireCommand(supplied.commandId());
        if (ledger.state() == RequirementPolicyRunState.POLICY_DECIDED) {
            requireDecidedReplay(ledger, task, decision, supplied, current);
            RequirementStageCommand apply = requireCommandByIdentity(task.taskId(), DELIVERY, APPLY, retryContext);
            requirePendingIdentity(apply, ledger, DELIVERY, APPLY, retryContext);
            return new RequirementPolicyEvaluationResult(ledger, current, apply,
                    ledger.boundTaskVersion(), ledger.boundFencingToken(), retryContext);
        }
        requirePlanReady(ledger, task, decision, supplied, current, owner);
        long nextVersion = Math.addExact(decision.expectedTaskVersion(), 1L);
        long nextFence = Math.addExact(decision.expectedFencingToken(), 1L);
        RequirementStageCommand apply = newPending(task, nextVersion, nextFence, DELIVERY, APPLY, ledger.id(), retryContext, nowEpochMillis);
        requireAbsentOrExact(apply);
        RequirementPolicyRun decided = decided(ledger, decision, nextVersion, nextFence, nowEpochMillis);
        RdTaskStatusEvent policyEvent = event(task, RdTaskStatus.WAITING_POLICY.name(),
                policyMessage(decision.policyAction(), decision.policyJson()), nowEpochMillis);
        // All reads, identities, conflict checks, and derived values are complete above this line.
        tasks.advanceStatusWithExpectedVersion(task.taskId(), task.version(), task.fencingToken(),
                RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY, null, null, null, null);
        events.save(policyEvent);
        RequirementStageCommand completed = commands.complete(supplied.commandId(), owner, nowEpochMillis);
        commands.enqueue(apply);
        policyRuns.compareAndSet(decided, RequirementPolicyRunState.PLAN_READY, ledger.ledgerVersion());
        return new RequirementPolicyEvaluationResult(decided, completed, apply, nextVersion, nextFence, retryContext);
    }

    /** Approves an exact waiting generation and creates its durable approval-resume command. */
    @Override
    public synchronized RequirementPolicyApprovalResult approve(
            ApproveRequirementPolicyCommand command, String actor, long nowEpochMillis
    ) {
        requireNormalContext(command.retryContext());
        return approve(command, RequirementPolicyRetryContext.empty(), actor, nowEpochMillis);
    }

    @Override
    public synchronized RequirementPolicyApprovalResult approve(
            ApproveRequirementPolicyCommand command, RequirementPolicyRetryContext retryContext,
            String actor, long nowEpochMillis
    ) {
        Objects.requireNonNull(command, "command must not be null");
        requireSameContext(retryContext, command.retryContext(), "approval command");
        String approvedBy = require(actor, "actor");
        requireClock(nowEpochMillis);
        RequirementPolicyRun ledger = requireRun(command.policyRunId());
        if (ledger.state() == RequirementPolicyRunState.APPROVED || ledger.state() == RequirementPolicyRunState.APPLIED) {
            return idempotentApproval(ledger, command, approvedBy, retryContext);
        }
        RdRequirementTask task = requireTask(command.taskId());
        requireWaitingApproval(ledger, task, command);
        long nextVersion = Math.addExact(command.expectedTaskVersion(), 1L);
        long nextFence = Math.addExact(command.expectedFencingToken(), 1L);
        RequirementStageCommand resume = newPending(task, nextVersion, nextFence, DELIVERY, RESUME, ledger.id(), retryContext, nowEpochMillis);
        requireAbsentOrExact(resume);
        RequirementPolicyRun approved = approved(ledger, command, approvedBy, resume.commandId(), nextVersion, nextFence, nowEpochMillis);
        RdTaskStatusEvent approvalEvent = event(task, RdTaskStatusEvent.ACTION_APPROVED, command.note(), nowEpochMillis);
        tasks.advanceStatusWithExpectedVersion(task.taskId(), task.version(), task.fencingToken(),
                RdTaskStatus.WAITING_APPROVAL, RdTaskStatus.WAITING_APPROVAL, null, null, null, null);
        events.save(approvalEvent);
        commands.enqueue(resume);
        policyRuns.compareAndSet(approved, RequirementPolicyRunState.WAITING_APPROVAL, ledger.ledgerVersion());
        return new RequirementPolicyApprovalResult(approved, resume, nextVersion, nextFence, retryContext);
    }

    /** Consumes a leased policy-apply command and either starts the first role or stops safely. */
    @Override
    public synchronized RequirementPolicyApplyResult consumePolicyApply(
            RequirementStageCommand supplied, String leaseOwner, long nowEpochMillis
    ) {
        requireNormalCommand(supplied);
        return consumePolicyApply(supplied, RequirementPolicyRetryContext.empty(), leaseOwner, nowEpochMillis);
    }

    @Override
    public synchronized RequirementPolicyApplyResult consumePolicyApply(
            RequirementStageCommand supplied, RequirementPolicyRetryContext retryContext,
            String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(supplied, "command must not be null");
        requireControlCommandContext(retryContext, supplied);
        String owner = require(leaseOwner, "leaseOwner");
        requireClock(nowEpochMillis);
        RequirementPolicyRun ledger = requireCommandLedger(supplied);
        RdRequirementTask task = requireTask(supplied.taskId());
        RequirementStageCommand current = requireCommand(supplied.commandId());
        if (ledger.state() != RequirementPolicyRunState.POLICY_DECIDED) {
            return replayApply(ledger, task, supplied, current, retryContext);
        }
        requireDecidedApply(ledger, task, supplied, current, owner);
        RequirementPolicyApplyDisposition disposition = disposition(ledger.policyAction());
        long nextVersion = Math.addExact(ledger.boundTaskVersion(), 1L);
        long nextFence = Math.addExact(ledger.boundFencingToken(), 1L);
        RdTaskStatus target = target(disposition);
        RequirementStageCommand next = disposition == RequirementPolicyApplyDisposition.ALLOWED
                ? newPending(task, nextVersion, nextFence, firstRole(), roleStage(), ledger.id(), retryContext, nowEpochMillis) : null;
        if (next == null) {
            requireNoContinuation(task.taskId(), retryContext);
        } else {
            requireAbsentOrExact(next);
        }
        RequirementPolicyRun consumed = applied(ledger, disposition, supplied.commandId(), nextVersion, nextFence, nowEpochMillis);
        RdTaskStatusEvent policyEvent = event(task, target.name(),
                policyMessage(ledger.policyAction(), ledger.policyJson()), nowEpochMillis);
        tasks.advanceStatusWithExpectedVersion(task.taskId(), task.version(), task.fencingToken(),
                RdTaskStatus.WAITING_POLICY, target, null, null, null, null);
        events.save(policyEvent);
        RequirementStageCommand completed = commands.complete(supplied.commandId(), owner, nowEpochMillis);
        if (next != null) {
            commands.enqueue(next);
        }
        policyRuns.compareAndSet(consumed, RequirementPolicyRunState.POLICY_DECIDED, ledger.ledgerVersion());
        if (disposition == RequirementPolicyApplyDisposition.ALLOWED) {
            initializeAuditedState(requireTask(supplied.taskId()));
        }
        return new RequirementPolicyApplyResult(consumed, completed, disposition, next, nextVersion, nextFence, retryContext);
    }

    /** Consumes an approved resume command and starts the exact first delivery role. */
    @Override
    public synchronized RequirementPolicyResumeResult consumeApproval(
            RequirementStageCommand supplied, String leaseOwner, long nowEpochMillis
    ) {
        requireNormalCommand(supplied);
        return consumeApproval(supplied, RequirementPolicyRetryContext.empty(), leaseOwner, nowEpochMillis);
    }

    @Override
    public synchronized RequirementPolicyResumeResult consumeApproval(
            RequirementStageCommand supplied, RequirementPolicyRetryContext retryContext,
            String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(supplied, "command must not be null");
        requireControlCommandContext(retryContext, supplied);
        String owner = require(leaseOwner, "leaseOwner");
        requireClock(nowEpochMillis);
        RequirementPolicyRun ledger = requireCommandLedger(supplied);
        RdRequirementTask task = requireTask(supplied.taskId());
        RequirementStageCommand current = requireCommand(supplied.commandId());
        if (ledger.state() == RequirementPolicyRunState.APPLIED) {
            requireAppliedReplay(ledger, task, supplied, current);
            RequirementStageCommand next = requireCommandByIdentity(task.taskId(), firstRole(), roleStage(), retryContext);
            requirePendingIdentity(next, ledger, firstRole(), roleStage(), retryContext);
            initializeAuditedState(task);
            return new RequirementPolicyResumeResult(ledger, current, next, ledger.boundTaskVersion(), ledger.boundFencingToken(), retryContext);
        }
        requireApprovedResume(ledger, task, supplied, current, owner);
        long nextVersion = Math.addExact(ledger.boundTaskVersion(), 1L);
        long nextFence = Math.addExact(ledger.boundFencingToken(), 1L);
        RequirementStageCommand next = newPending(task, nextVersion, nextFence, firstRole(), roleStage(), ledger.id(), retryContext, nowEpochMillis);
        requireAbsentOrExact(next);
        RequirementPolicyRun applied = appliedResume(ledger, supplied.commandId(), nextVersion, nextFence, nowEpochMillis);
        RdTaskStatusEvent executingEvent = event(task, RdTaskStatus.EXECUTING.name(), "", nowEpochMillis);
        tasks.advanceStatusWithExpectedVersion(task.taskId(), task.version(), task.fencingToken(),
                RdTaskStatus.WAITING_APPROVAL, RdTaskStatus.EXECUTING, null, null, null, null);
        events.save(executingEvent);
        RequirementStageCommand completed = commands.complete(supplied.commandId(), owner, nowEpochMillis);
        commands.enqueue(next);
        policyRuns.compareAndSet(applied, RequirementPolicyRunState.APPROVED, ledger.ledgerVersion());
        initializeAuditedState(requireTask(supplied.taskId()));
        return new RequirementPolicyResumeResult(applied, completed, next, nextVersion, nextFence, retryContext);
    }

    private RequirementPolicyApplyResult replayApply(RequirementPolicyRun ledger, RdRequirementTask task,
            RequirementStageCommand supplied, RequirementStageCommand current, RequirementPolicyRetryContext retryContext) {
        RequirementPolicyApplyDisposition disposition = switch (ledger.state()) {
            case APPLIED -> RequirementPolicyApplyDisposition.ALLOWED;
            case WAITING_APPROVAL -> RequirementPolicyApplyDisposition.WAITING_APPROVAL;
            case DENIED -> RequirementPolicyApplyDisposition.DENIED;
            default -> throw new IllegalStateException("policy-apply ledger is not replayable: " + ledger.id());
        };
        if (!ledger.id().equals(supplied.policyRunId()) || current.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutable(supplied, current) || task.version() != ledger.boundTaskVersion()
                || task.fencingToken() != ledger.boundFencingToken() || task.status() != target(disposition)) {
            throw new IllegalStateException("policy-apply replay identity conflicts with ledger");
        }
        RequirementStageCommand next = disposition == RequirementPolicyApplyDisposition.ALLOWED
                ? requireCommandByIdentity(task.taskId(), firstRole(), roleStage(), retryContext) : null;
        if (next != null) requirePendingIdentity(next, ledger, firstRole(), roleStage(), retryContext); else requireNoContinuation(task.taskId(), retryContext);
        if (disposition == RequirementPolicyApplyDisposition.ALLOWED) {
            initializeAuditedState(requireTask(supplied.taskId()));
        }
        return new RequirementPolicyApplyResult(ledger, current, disposition, next, ledger.boundTaskVersion(), ledger.boundFencingToken(), retryContext);
    }

    private RequirementPolicyApprovalResult idempotentApproval(RequirementPolicyRun ledger,
            ApproveRequirementPolicyCommand command, String actor, RequirementPolicyRetryContext retryContext) {
        if (!ledger.taskId().equals(command.taskId()) || !"WAITING_APPROVAL".equals(ledger.policyAction())
                || !ledger.approvalRequestId().equals(command.approvalRequestId()) || !ledger.approvedBy().equals(actor)
                || !ledger.note().equals(command.note()) || !ledger.planDigest().equals(command.planDigest())
                || !ledger.policyDigest().equals(command.policyDigest())
                || !Objects.equals(ledger.approvalExpectedTaskVersion(), command.expectedTaskVersion())
                || !Objects.equals(ledger.approvalExpectedFencingToken(), command.expectedFencingToken())) {
            throw new IllegalStateException("approval idempotency payload conflicts with policy run: " + command.policyRunId());
        }
        RequirementStageCommand resume = requireCommand(ledger.approvalResumeCommandId());
        if (!ledger.id().equals(resume.policyRunId()) || !resume.taskId().equals(command.taskId())
                || resume.taskVersion() != Math.addExact(command.expectedTaskVersion(), 1L)
                || resume.fencingToken() != Math.addExact(command.expectedFencingToken(), 1L)
                || !DELIVERY.equals(resume.role()) || !RESUME.equals(resume.stage())) {
            throw new IllegalStateException("approved policy run resume command does not match its ledger: " + ledger.id());
        }
        retryContext.requireControlCommandIdentity(resume.retryCheckpointId(), resume.businessGeneration(),
                resume.targetRetryBindingId());
        return new RequirementPolicyApprovalResult(ledger, resume, resume.taskVersion(), resume.fencingToken(), retryContext);
    }

    private void requirePlanReady(RequirementPolicyRun ledger, RdRequirementTask task,
            RecordRequirementPolicyDecisionCommand decision, RequirementStageCommand supplied,
            RequirementStageCommand current, String owner) {
        if (ledger.state() != RequirementPolicyRunState.PLAN_READY || !ledger.taskId().equals(decision.taskId())
                || ledger.sourceTaskVersion() != decision.expectedTaskVersion() || ledger.sourceFencingToken() != decision.expectedFencingToken()
                || ledger.boundTaskVersion() != decision.expectedTaskVersion() || ledger.boundFencingToken() != decision.expectedFencingToken()
                || !ledger.planDigest().equals(decision.planDigest()) || task.status() != RdTaskStatus.PLAN_GENERATED
                || task.version() != decision.expectedTaskVersion() || task.fencingToken() != decision.expectedFencingToken()
                || current.status() != RequirementStageCommand.Status.RUNNING || !owner.equals(supplied.leaseOwner())
                || !owner.equals(current.leaseOwner()) || !sameImmutable(supplied, current)
                || !DELIVERY.equals(current.role()) || !EVALUATE.equals(current.stage())
                || !ledger.id().equals(current.policyRunId())) throw new IllegalStateException("policy-evaluation command, task, or ledger identity is stale");
    }

    private void requireDecidedReplay(RequirementPolicyRun ledger, RdRequirementTask task,
            RecordRequirementPolicyDecisionCommand decision, RequirementStageCommand supplied, RequirementStageCommand current) {
        if (!ledger.id().equals(supplied.policyRunId()) || !ledger.taskId().equals(decision.taskId())
                || ledger.sourceTaskVersion() != decision.expectedTaskVersion() || ledger.sourceFencingToken() != decision.expectedFencingToken()
                || !ledger.planDigest().equals(decision.planDigest()) || !ledger.policyJson().equals(decision.policyJson())
                || !ledger.policyDigest().equals(decision.policyDigest()) || !ledger.policyAction().equals(decision.policyAction())
                || task.status() != RdTaskStatus.WAITING_POLICY || task.version() != ledger.boundTaskVersion()
                || task.fencingToken() != ledger.boundFencingToken() || current.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutable(supplied, current)) throw new IllegalStateException("decided policy-evaluation replay identity conflicts with ledger");
    }

    private void requireWaitingApproval(RequirementPolicyRun ledger, RdRequirementTask task, ApproveRequirementPolicyCommand command) {
        if (ledger.state() != RequirementPolicyRunState.WAITING_APPROVAL || !ledger.taskId().equals(command.taskId())
                || !ledger.planDigest().equals(command.planDigest()) || !ledger.policyDigest().equals(command.policyDigest())
                || ledger.boundTaskVersion() != command.expectedTaskVersion() || ledger.boundFencingToken() != command.expectedFencingToken()
                || task.status() != RdTaskStatus.WAITING_APPROVAL || task.version() != command.expectedTaskVersion()
                || task.fencingToken() != command.expectedFencingToken()) throw new IllegalStateException("approval request does not match waiting policy ledger");
    }

    private void requireDecidedApply(RequirementPolicyRun ledger, RdRequirementTask task,
            RequirementStageCommand supplied, RequirementStageCommand current, String owner) {
        if (!supported(ledger.policyAction()) || !ledger.id().equals(supplied.policyRunId())
                || ledger.boundTaskVersion() != supplied.taskVersion() || ledger.boundFencingToken() != supplied.fencingToken()
                || task.status() != RdTaskStatus.WAITING_POLICY || task.version() != ledger.boundTaskVersion()
                || task.fencingToken() != ledger.boundFencingToken() || current.status() != RequirementStageCommand.Status.RUNNING
                || !owner.equals(supplied.leaseOwner()) || !owner.equals(current.leaseOwner()) || !sameImmutable(supplied, current)
                || !DELIVERY.equals(current.role()) || !APPLY.equals(current.stage())) throw new IllegalStateException("policy-apply command, task, or ledger identity is stale");
    }

    private void requireApprovedResume(RequirementPolicyRun ledger, RdRequirementTask task,
            RequirementStageCommand supplied, RequirementStageCommand current, String owner) {
        if (ledger.state() != RequirementPolicyRunState.APPROVED || !"WAITING_APPROVAL".equals(ledger.policyAction())
                || !ledger.id().equals(supplied.policyRunId()) || !ledger.approvalResumeCommandId().equals(supplied.commandId())
                || task.status() != RdTaskStatus.WAITING_APPROVAL || task.version() != ledger.boundTaskVersion()
                || task.fencingToken() != ledger.boundFencingToken() || current.status() != RequirementStageCommand.Status.RUNNING
                || !owner.equals(supplied.leaseOwner()) || !owner.equals(current.leaseOwner()) || !sameImmutable(supplied, current)
                || !DELIVERY.equals(current.role()) || !RESUME.equals(current.stage())) throw new IllegalStateException("approval-resume command, task, or ledger identity is stale");
    }

    private void requireAppliedReplay(RequirementPolicyRun ledger, RdRequirementTask task,
            RequirementStageCommand supplied, RequirementStageCommand current) {
        if (!"WAITING_APPROVAL".equals(ledger.policyAction()) || !ledger.id().equals(supplied.policyRunId())
                || !ledger.approvalResumeCommandId().equals(supplied.commandId()) || !ledger.consumedByCommandId().equals(supplied.commandId())
                || task.status() != RdTaskStatus.EXECUTING || task.version() != ledger.boundTaskVersion()
                || task.fencingToken() != ledger.boundFencingToken() || current.status() != RequirementStageCommand.Status.SUCCEEDED
                || !sameImmutable(supplied, current)) throw new IllegalStateException("applied approval-resume replay identity conflicts with ledger");
    }

    private RequirementPolicyRun requireRun(String id) { return policyRuns.findById(id).orElseThrow(() -> new IllegalStateException("policy run is missing: " + id)); }
    private RequirementPolicyRun requireCommandLedger(RequirementStageCommand command) { if (command.policyRunId().isBlank()) throw new IllegalStateException("policy command is missing policyRunId: " + command.commandId()); return requireRun(command.policyRunId()); }
    private RdRequirementTask requireTask(String id) { return tasks.findRequirementTask(id).orElseThrow(() -> new IllegalStateException("requirement task is missing: " + id)); }

    private void initializeAuditedState(RdRequirementTask task) {
        new AuditedTaskStatePolicyBootstrap(auditedTaskStateStore).initializeIfAbsent(task);
    }
    private RequirementStageCommand requireCommand(String id) { return commands.findById(id).orElseThrow(() -> new IllegalStateException("stage command is missing: " + id)); }
    private RequirementStageCommand requireCommandByIdentity(
            String task, String role, String stage, RequirementPolicyRetryContext retryContext
    ) {
        return commands.find(task, role, stage, retryContext.checkpointId())
                .orElseThrow(() -> new IllegalStateException("stage command is missing: " + task + "/" + stage));
    }
    private void requireAbsentOrExact(RequirementStageCommand requested) {
        commands.find(requested.taskId(), requested.role(), requested.stage(), requested.retryCheckpointId())
                .ifPresent(existing -> {
                    if (!existing.equals(requested)) {
                        throw new IllegalStateException("stage command identity conflicts: " + requested.stage());
                    }
                });
    }
    private void requireNoContinuation(String task, RequirementPolicyRetryContext retryContext) {
        if (commands.find(task, firstRole(), roleStage(), retryContext.checkpointId()).isPresent()) {
            throw new IllegalStateException("non-allowed policy action has a first role continuation: " + task);
        }
    }
    private void requirePendingIdentity(RequirementStageCommand command, RequirementPolicyRun ledger,
            String role, String stage, RequirementPolicyRetryContext retryContext) {
        if (!ledger.id().equals(command.policyRunId()) || command.status() != RequirementStageCommand.Status.PENDING
                || command.taskVersion() != ledger.boundTaskVersion() || command.fencingToken() != ledger.boundFencingToken()
                || !role.equals(command.role()) || !stage.equals(command.stage())) {
            throw new IllegalStateException("policy continuation identity conflicts with ledger");
        }
        if (role.equals(DELIVERY)) {
            retryContext.requireControlCommandIdentity(command.retryCheckpointId(), command.businessGeneration(),
                    command.targetRetryBindingId());
        } else {
            retryContext.requireContinuationIdentity(command.retryCheckpointId(), command.businessGeneration(),
                    command.targetRetryBindingId());
        }
    }

    private RequirementStageCommand newPending(RdRequirementTask task, long version, long fence, String role,
            String stage, String policyRunId, RequirementPolicyRetryContext retryContext, long now) {
        var requirements = FairRequirementDeliveryClaimPlanner.classifyStageRequirements(role, stage);
        ScheduleResourceClass resource = requirements.stream().filter(value -> value != ScheduleResourceClass.GENERIC).findFirst().orElse(ScheduleResourceClass.GENERIC);
        if (retryContext.isEmpty()) {
            return RequirementStageCommand.pending(ids.nextIdString(), task.taskId(), version, fence, role, stage, 0, 3,
                    Math.addExact(now, COMMAND_DEADLINE_MILLIS), resource, requirements, task.projectId(), "memory", task.priority(), policyRunId, now);
        }
        String targetBinding = role.equals(DELIVERY) ? "" : retryContext.targetRetryBindingId();
        return RequirementStageCommand.pending(ids.nextIdString(), task.taskId(), version, fence, role, stage, 0, 3,
                Math.addExact(now, COMMAND_DEADLINE_MILLIS), resource, requirements, task.projectId(), "memory", task.priority(), policyRunId,
                retryContext.checkpointId(), retryContext.businessGeneration(), targetBinding, now);
    }
    private RdTaskStatusEvent event(RdRequirementTask task, String status, String message, long now) { return new RdTaskStatusEvent(ids.nextIdString(), task.taskId(), status, task.title(), message, now, 0L, RdTaskEventTrigger.SYSTEM.name()); }
    private static String firstRole() { return AgentRole.requirementDeliveryOrder().getFirst().name(); }
    private static String roleStage() { return "ROLE_EXECUTION:" + firstRole(); }
    private static boolean sameImmutable(RequirementStageCommand a, RequirementStageCommand b) { return a.commandId().equals(b.commandId()) && a.taskId().equals(b.taskId()) && a.taskVersion() == b.taskVersion() && a.fencingToken() == b.fencingToken() && a.role().equals(b.role()) && a.stage().equals(b.stage()) && a.policyRunId().equals(b.policyRunId()) && a.retryCheckpointId().equals(b.retryCheckpointId()) && a.businessGeneration() == b.businessGeneration() && a.targetRetryBindingId().equals(b.targetRetryBindingId()); }
    private static void requireSameContext(RequirementPolicyRetryContext supplied,
            RequirementPolicyRetryContext embedded, String carrier) {
        if (!Objects.requireNonNull(supplied, "retryContext must not be null")
                .equals(Objects.requireNonNull(embedded, carrier + " retryContext must not be null"))) {
            throw new IllegalArgumentException("retry context does not match " + carrier);
        }
    }
    private static void requireNormalContext(RequirementPolicyRetryContext context) {
        if (context == null || !context.isEmpty()) {
            throw new IllegalArgumentException("legacy policy operation requires an empty retry context");
        }
    }
    private static void requireNormalCommand(RequirementStageCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RequirementPolicyRetryContext.empty().requireControlCommandIdentity(command.retryCheckpointId(),
                command.businessGeneration(), command.targetRetryBindingId());
    }
    private static void requireControlCommandContext(RequirementPolicyRetryContext context,
            RequirementStageCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(context, "retryContext must not be null").requireControlCommandIdentity(
                command.retryCheckpointId(), command.businessGeneration(), command.targetRetryBindingId());
    }
    private static RequirementPolicyApplyDisposition disposition(String action) { return switch (action) { case "ALLOWED" -> RequirementPolicyApplyDisposition.ALLOWED; case "WAITING_APPROVAL" -> RequirementPolicyApplyDisposition.WAITING_APPROVAL; case "NEED_INFO", "UNSAFE" -> RequirementPolicyApplyDisposition.DENIED; default -> throw new IllegalStateException("unsupported stored policy action: " + action); }; }
    private static boolean supported(String action) { return "ALLOWED".equals(action) || "WAITING_APPROVAL".equals(action) || "NEED_INFO".equals(action) || "UNSAFE".equals(action); }
    private static RdTaskStatus target(RequirementPolicyApplyDisposition value) { return switch (value) { case ALLOWED -> RdTaskStatus.EXECUTING; case WAITING_APPROVAL -> RdTaskStatus.WAITING_APPROVAL; case DENIED -> RdTaskStatus.FAILED_NEEDS_HUMAN; }; }
    private static void requireClock(long now) { if (now < 0L) throw new IllegalArgumentException("nowEpochMillis must not be negative"); }
    private static String require(String value, String name) { String normalized = value == null ? "" : value.strip(); if (normalized.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); return normalized; }
    private static String policyMessage(String action, String json) { try { JsonNode reason = JSON.readTree(json).path("reason"); return reason.isTextual() && !reason.textValue().isBlank() ? action + ": " + reason.textValue().strip() : action; } catch (JsonProcessingException error) { throw new IllegalStateException("stored policy JSON is invalid despite ledger digest validation", error); } }
    private static RequirementPolicyRun decided(RequirementPolicyRun current, RecordRequirementPolicyDecisionCommand d, long v, long f, long now) { return new RequirementPolicyRun(current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(), current.planJson(), current.planDigest(), d.policyJson(), d.policyDigest(), d.policyAction(), RequirementPolicyRunState.POLICY_DECIDED, v, f, null, null, "", "", "", 0L, "", "", 0L, current.ledgerVersion() + 1L, current.createdAtEpochMillis(), now); }
    private static RequirementPolicyRun applied(RequirementPolicyRun current, RequirementPolicyApplyDisposition d, String command, long v, long f, long now) { RequirementPolicyRunState state = d == RequirementPolicyApplyDisposition.ALLOWED ? RequirementPolicyRunState.APPLIED : d == RequirementPolicyApplyDisposition.WAITING_APPROVAL ? RequirementPolicyRunState.WAITING_APPROVAL : RequirementPolicyRunState.DENIED; boolean consumed = d != RequirementPolicyApplyDisposition.WAITING_APPROVAL; return new RequirementPolicyRun(current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(), current.planJson(), current.planDigest(), current.policyJson(), current.policyDigest(), current.policyAction(), state, v, f, null, null, "", "", "", 0L, "", consumed ? command : "", consumed ? now : 0L, current.ledgerVersion() + 1L, current.createdAtEpochMillis(), now); }
    private static RequirementPolicyRun approved(RequirementPolicyRun current, ApproveRequirementPolicyCommand c, String actor, String resume, long v, long f, long now) { return new RequirementPolicyRun(current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(), current.planJson(), current.planDigest(), current.policyJson(), current.policyDigest(), current.policyAction(), RequirementPolicyRunState.APPROVED, v, f, c.expectedTaskVersion(), c.expectedFencingToken(), c.approvalRequestId(), actor, c.note(), now, resume, "", 0L, current.ledgerVersion() + 1L, current.createdAtEpochMillis(), now); }
    private static RequirementPolicyRun appliedResume(RequirementPolicyRun current, String command, long v, long f, long now) { return new RequirementPolicyRun(current.id(), current.taskId(), current.sourceTaskVersion(), current.sourceFencingToken(), current.planJson(), current.planDigest(), current.policyJson(), current.policyDigest(), current.policyAction(), RequirementPolicyRunState.APPLIED, v, f, current.approvalExpectedTaskVersion(), current.approvalExpectedFencingToken(), current.approvalRequestId(), current.approvedBy(), current.note(), current.approvedAtEpochMillis(), current.approvalResumeCommandId(), command, now, current.ledgerVersion() + 1L, current.createdAtEpochMillis(), now); }
}
