package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskStatusEventMapper;
import com.wish.rd.bootstrap.threading.RequirementStageCommandFactory;
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
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * PostgreSQL operator-answer transaction.
 *
 * <p>Must not be {@code final}: {@code @Transactional} uses CGLIB subclassing.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRequirementUserAnswerTransactionAdapter implements RequirementUserAnswerTransactionPort {
    private static final String DELIVERY = "REQUIREMENT_DELIVERY";

    private final RdTaskMapper taskMapper;
    private final RdTaskStatusEventMapper eventMapper;
    private final RequirementStageCommandStore commands;
    private final RequirementStageCommandFactory commandFactory;
    private final ManagerDecisionStore decisions;
    private final SnowflakeIdGenerator ids;

    public PostgresRequirementUserAnswerTransactionAdapter(
            RdTaskMapper taskMapper,
            RdTaskStatusEventMapper eventMapper,
            RequirementStageCommandStore commands,
            RequirementStageCommandFactory commandFactory,
            ManagerDecisionStore decisions,
            SnowflakeIdGenerator ids
    ) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    @Override
    @Transactional
    public RequirementUserAnswerResult answer(AnswerRequirementCommand command, String actor, long nowEpochMillis) {
        Objects.requireNonNull(command, "command must not be null");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(command.taskId()));
        if (task == null || !"REQUIREMENT".equals(task.taskType)) {
            throw new IllegalStateException("requirement task is missing: " + command.taskId());
        }
        if (Boolean.TRUE.equals(task.paused)) {
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
        if (!RdTaskStatus.WAITING_USER_INPUT.name().equals(task.status)
                || number(task.version) != command.expectedTaskVersion()
                || number(task.fencingToken) != command.expectedTaskFence()) {
            throw new IllegalStateException("answer request does not match WAITING_USER_INPUT snapshot");
        }
        long nextVersion = Math.addExact(command.expectedTaskVersion(), 1L);
        long nextFence = Math.addExact(command.expectedTaskFence(), 1L);
        int changed = taskMapper.advanceStatusWithExpectedVersionFenced(
                task.id, command.expectedTaskVersion(), command.expectedTaskFence(),
                RdTaskStatus.WAITING_USER_INPUT.name(), RdTaskStatus.WAITING_USER_INPUT.name(),
                null, null, null, null, PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (changed != 1) {
            throw new IllegalStateException("answer task compare-and-set failed: " + command.taskId());
        }
        appendEvent(task, RdTaskStatus.WAITING_USER_INPUT.name(), command.answerText(), nowEpochMillis,
                RdTaskEventTrigger.MANUAL);
        RequirementStageCommand resume = commandFactory.createPendingCommand(
                command.taskId(), nextVersion, nextFence, DELIVERY, resumeStage,
                safe(task.projectId), safe(task.priority), "", "", nowEpochMillis);
        commands.enqueue(resume);
        RequirementStageCommand persisted = commands.find(command.taskId(), DELIVERY, resumeStage)
                .orElseThrow(() -> new IllegalStateException("answer resume was not persisted"));
        return new RequirementUserAnswerResult(persisted, nextVersion, nextFence);
    }

    @Override
    @Transactional
    public RequirementUserAnswerResumeResult consumeAnswer(
            RequirementStageCommand supplied, String leaseOwner, long nowEpochMillis
    ) {
        Objects.requireNonNull(supplied, "command must not be null");
        String owner = require(leaseOwner, "leaseOwner");
        if (!UserAnswerResumeStages.isResume(supplied.stage())) {
            throw new IllegalStateException("not a user-answer-resume command: " + supplied.commandId());
        }
        RdTaskRow task = taskMapper.lockByIdForUpdate(PostgresPersistenceSupport.parseId(supplied.taskId()));
        RequirementStageCommand current = commands.findById(supplied.commandId())
                .orElseThrow(() -> new IllegalStateException("stage command is missing: " + supplied.commandId()));
        String managerStage = ManagerDecideStages.forSource(current.commandId());
        if (current.status() == RequirementStageCommand.Status.SUCCEEDED) {
            RequirementStageCommand next = commands.find(supplied.taskId(), DELIVERY, managerStage)
                    .orElseThrow(() -> new IllegalStateException("manager continuation is missing"));
            return new RequirementUserAnswerResumeResult(
                    current, next, number(task.version), number(task.fencingToken));
        }
        if (task == null || !RdTaskStatus.WAITING_USER_INPUT.name().equals(task.status)
                || current.status() != RequirementStageCommand.Status.RUNNING
                || !owner.equals(current.leaseOwner())) {
            throw new IllegalStateException("user-answer-resume command or task identity is stale");
        }
        long nextVersion = Math.addExact(number(task.version), 1L);
        long nextFence = Math.addExact(number(task.fencingToken), 1L);
        int changed = taskMapper.advanceStatusWithExpectedVersionFenced(
                task.id, number(task.version), number(task.fencingToken),
                RdTaskStatus.WAITING_USER_INPUT.name(), RdTaskStatus.EXECUTING.name(),
                null, null, null, null, PostgresPersistenceSupport.toDateTime(nowEpochMillis));
        if (changed != 1) {
            throw new IllegalStateException("user-answer-resume task compare-and-set failed");
        }
        appendEvent(task, RdTaskStatus.EXECUTING.name(), "", nowEpochMillis, RdTaskEventTrigger.SYSTEM);
        RequirementStageCommand completed = commands.complete(supplied, owner, nowEpochMillis);
        RequirementStageCommand next = commandFactory.createPendingCommand(
                supplied.taskId(), nextVersion, nextFence, DELIVERY, managerStage,
                safe(task.projectId), safe(task.priority), "", "", nowEpochMillis);
        commands.enqueue(next);
        RequirementStageCommand persisted = commands.find(supplied.taskId(), DELIVERY, managerStage)
                .orElseThrow(() -> new IllegalStateException("manager continuation was not persisted"));
        return new RequirementUserAnswerResumeResult(completed, persisted, nextVersion, nextFence);
    }

    private void appendEvent(
            RdTaskRow task, String status, String message, long nowEpochMillis, RdTaskEventTrigger trigger
    ) {
        RdTaskStatusEventRow event = new RdTaskStatusEventRow();
        event.id = PostgresPersistenceSupport.parseId(ids.nextIdString());
        event.taskId = task.id;
        event.status = status;
        event.title = safe(task.title);
        event.message = message == null ? "" : message;
        event.enteredAt = PostgresPersistenceSupport.toDateTime(nowEpochMillis);
        event.durationMs = 0L;
        event.trigger = trigger.name();
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("answer timeline append failed: " + task.id);
        }
    }

    private static long number(Long value) {
        return value == null ? 0L : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
