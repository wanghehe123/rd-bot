package com.wish.rd.engine.requirement.answer.impl;

import com.wish.rd.engine.requirement.answer.AnswerRequirementCommand;
import com.wish.rd.engine.requirement.answer.RequirementUserAnswerResult;
import com.wish.rd.engine.requirement.answer.RequirementUserAnswerResumeResult;
import com.wish.rd.engine.requirement.answer.RequirementUserAnswerTransactionPort;
import com.wish.rd.engine.requirement.answer.UserAnswerResumeStages;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.manager.ManagerDecideStages;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.engine.requirement.manager.ManagerRoute;
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
import java.util.Set;

/** Single-runtime operator-answer transaction for memory mode. */
public final class InMemoryRequirementUserAnswerTransactionAdapter implements RequirementUserAnswerTransactionPort {
    private static final String DELIVERY = "REQUIREMENT_DELIVERY";
    private static final long COMMAND_DEADLINE_MILLIS = 300_000L;

    private final RdTaskStore tasks;
    private final RdTaskStatusEventStore events;
    private final RequirementStageCommandStore commands;
    private final ManagerDecisionStore decisions;
    private final SnowflakeIdGenerator ids;

    public InMemoryRequirementUserAnswerTransactionAdapter(
            RdTaskStore tasks,
            RdTaskStatusEventStore events,
            RequirementStageCommandStore commands,
            ManagerDecisionStore decisions,
            SnowflakeIdGenerator ids
    ) {
        this.tasks = Objects.requireNonNull(tasks, "tasks must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    @Override
    public synchronized RequirementUserAnswerResult answer(
            AnswerRequirementCommand command, String actor, long nowEpochMillis
    ) {
        Objects.requireNonNull(command, "command must not be null");
        require(actor, "actor");
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }
        RdRequirementTask task = tasks.findRequirementTask(command.taskId())
                .orElseThrow(() -> new IllegalStateException("requirement task is missing: " + command.taskId()));
        if (task.paused()) {
            throw new IllegalStateException("paused task cannot accept an operator answer: " + command.taskId());
        }
        ManagerDecision latest = decisions.findLatest(command.taskId())
                .orElseThrow(() -> new IllegalStateException("manager decision is missing: " + command.taskId()));
        if (latest.route() != ManagerRoute.ASK || !latest.decisionHash().equals(command.decisionHash())) {
            throw new IllegalStateException("answer does not match the latest ASK decision: " + command.taskId());
        }
        String resumeStage = UserAnswerResumeStages.forSource(latest.sourceCommandId());
        RequirementStageCommand existing = commands.find(command.taskId(), DELIVERY, resumeStage).orElse(null);
        if (existing != null) {
            if (existing.taskVersion() != Math.addExact(command.expectedTaskVersion(), 1L)
                    || existing.fencingToken() != Math.addExact(command.expectedTaskFence(), 1L)) {
                throw new IllegalStateException("answer resume identity conflicts: " + command.taskId());
            }
            return new RequirementUserAnswerResult(existing, existing.taskVersion(), existing.fencingToken());
        }
        if (task.status() != RdTaskStatus.WAITING_USER_INPUT
                || task.version() != command.expectedTaskVersion()
                || task.fencingToken() != command.expectedTaskFence()) {
            throw new IllegalStateException("answer request does not match WAITING_USER_INPUT snapshot");
        }
        long nextVersion = Math.addExact(command.expectedTaskVersion(), 1L);
        long nextFence = Math.addExact(command.expectedTaskFence(), 1L);
        RequirementStageCommand resume = pending(task, nextVersion, nextFence, resumeStage, nowEpochMillis);
        tasks.advanceStatusWithExpectedVersion(
                task.taskId(), task.version(), task.fencingToken(),
                RdTaskStatus.WAITING_USER_INPUT, RdTaskStatus.WAITING_USER_INPUT,
                null, null, null, null);
        events.save(new RdTaskStatusEvent(
                ids.nextIdString(), task.taskId(), RdTaskStatus.WAITING_USER_INPUT.name(), task.title(),
                command.answerText(), nowEpochMillis, 0L, RdTaskEventTrigger.MANUAL.name()));
        commands.enqueue(resume);
        return new RequirementUserAnswerResult(resume, nextVersion, nextFence);
    }

    @Override
    public synchronized RequirementUserAnswerResumeResult consumeAnswer(
            RequirementStageCommand supplied, String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(supplied, "command must not be null");
        String owner = require(leaseOwner, "leaseOwner");
        if (!UserAnswerResumeStages.isResume(supplied.stage())) {
            throw new IllegalStateException("not a user-answer-resume command: " + supplied.commandId());
        }
        RequirementStageCommand current = commands.findById(supplied.commandId())
                .orElseThrow(() -> new IllegalStateException("stage command is missing: " + supplied.commandId()));
        RdRequirementTask task = tasks.findRequirementTask(supplied.taskId())
                .orElseThrow(() -> new IllegalStateException("requirement task is missing: " + supplied.taskId()));
        if (current.status() == RequirementStageCommand.Status.SUCCEEDED) {
            RequirementStageCommand next = commands.find(
                    task.taskId(), DELIVERY, ManagerDecideStages.forSource(current.commandId()))
                    .orElseThrow(() -> new IllegalStateException("manager continuation is missing"));
            return new RequirementUserAnswerResumeResult(
                    current, next, task.version(), task.fencingToken());
        }
        if (task.status() != RdTaskStatus.WAITING_USER_INPUT
                || current.status() != RequirementStageCommand.Status.RUNNING
                || !owner.equals(current.leaseOwner())) {
            throw new IllegalStateException("user-answer-resume command or task identity is stale");
        }
        long nextVersion = Math.addExact(task.version(), 1L);
        long nextFence = Math.addExact(task.fencingToken(), 1L);
        RequirementStageCommand next = pending(
                task, nextVersion, nextFence, ManagerDecideStages.forSource(current.commandId()), nowEpochMillis);
        tasks.advanceStatusWithExpectedVersion(
                task.taskId(), task.version(), task.fencingToken(),
                RdTaskStatus.WAITING_USER_INPUT, RdTaskStatus.EXECUTING,
                null, null, null, null);
        events.save(new RdTaskStatusEvent(
                ids.nextIdString(), task.taskId(), RdTaskStatus.EXECUTING.name(), task.title(),
                "", nowEpochMillis, 0L, RdTaskEventTrigger.SYSTEM.name()));
        RequirementStageCommand completed = commands.complete(supplied.commandId(), owner, nowEpochMillis);
        commands.enqueue(next);
        return new RequirementUserAnswerResumeResult(completed, next, nextVersion, nextFence);
    }

    private RequirementStageCommand pending(
            RdRequirementTask task, long version, long fence, String stage, long now
    ) {
        Set<ScheduleResourceClass> requirements =
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(DELIVERY, stage);
        ScheduleResourceClass resource = requirements.stream()
                .filter(value -> value != ScheduleResourceClass.GENERIC)
                .findFirst()
                .orElse(ScheduleResourceClass.GENERIC);
        return RequirementStageCommand.pending(
                ids.nextIdString(), task.taskId(), version, fence, DELIVERY, stage, 0, 3,
                Math.addExact(now, COMMAND_DEADLINE_MILLIS), resource, requirements,
                task.projectId(), "memory", task.priority(), now);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
