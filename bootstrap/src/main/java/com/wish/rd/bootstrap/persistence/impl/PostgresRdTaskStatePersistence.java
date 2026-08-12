package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.runtime.RdTaskStatePersistence;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** PostgreSQL transaction boundary for a task snapshot and its append-only state event. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresRdTaskStatePersistence implements RdTaskStatePersistence {

    private final PostgresRdTaskStore taskStore;
    private final PostgresRdTaskStatusEventStore eventStore;

    public PostgresRdTaskStatePersistence(
            PostgresRdTaskStore taskStore,
            PostgresRdTaskStatusEventStore eventStore
    ) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore must not be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
    }

    @Override
    @Transactional
    public RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event) {
        Objects.requireNonNull(task, "task must not be null");
        RdTask saved = switch (task) {
            case RdBugFixTask bugFixTask -> taskStore.saveBugFixTask(bugFixTask);
            case RdRequirementTask requirementTask -> taskStore.saveRequirementTask(requirementTask);
            default -> throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
        };
        if (event != null) {
            eventStore.save(event);
        }
        return saved;
    }

    @Override
    @Transactional
    public RdTask saveWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(expectedStatus, "expectedStatus must not be null");
        if (task.status() == null) {
            throw new IllegalArgumentException("task status must not be null");
        }
        taskStore.advanceStatusWithExpectedVersion(
                task.taskId(), expectedVersion, expectedFencingToken, expectedStatus, task.status(),
                task.errorMessage(), executionResultJson(task), pullRequestUrl(task), promptSnapshot(task)
        );
        RdTask saved = taskStore.findTask(task.taskId()).orElseThrow(
                () -> new IllegalStateException("task disappeared after fenced CAS: " + task.taskId()));
        if (event != null) {
            eventStore.save(event);
        }
        return saved;
    }

    @Override
    @Transactional
    public RdTask saveActionWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(expectedStatus, "expectedStatus must not be null");
        taskStore.updateTaskWithExpectedVersion(
                task, expectedVersion, expectedFencingToken, expectedStatus);
        RdTask saved = taskStore.findTask(task.taskId()).orElseThrow(
                () -> new IllegalStateException("task disappeared after fenced snapshot update: " + task.taskId()));
        if (event != null) {
            eventStore.save(event);
        }
        return saved;
    }

    private String executionResultJson(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.executionResultJson();
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.executionResultJson();
        }
        return null;
    }

    private String pullRequestUrl(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.pullRequestUrl();
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.pullRequestUrl();
        }
        return null;
    }

    private String promptSnapshot(RdTask task) {
        if (task instanceof RdBugFixTask bugFixTask) {
            return bugFixTask.promptSnapshot();
        }
        if (task instanceof RdRequirementTask requirementTask) {
            return requirementTask.promptSnapshot();
        }
        return null;
    }
}
