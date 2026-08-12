package com.wish.rd.rag.runtime.impl;

import com.wish.rd.rag.runtime.RdTaskStatePersistence;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;

import java.util.Objects;

/** Local/test task-state persistence composed from the existing snapshot and event stores. */
public final class CoordinatedRdTaskStatePersistence implements RdTaskStatePersistence {

    private final RdTaskStore taskStore;
    private final RdTaskStatusEventStore eventStore;
    private final InMemoryRdTaskStore inMemoryTaskStore;

    public CoordinatedRdTaskStatePersistence(RdTaskStore taskStore, RdTaskStatusEventStore eventStore) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore must not be null");
        this.eventStore = eventStore;
        this.inMemoryTaskStore = taskStore instanceof InMemoryRdTaskStore memoryStore ? memoryStore : null;
    }

    @Override
    public RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event) {
        Objects.requireNonNull(task, "task must not be null");
        return withinInMemoryStoreBoundary(() -> {
            RdTask before = taskStore.findTask(task.taskId()).orElse(null);
            return saveEventOrRestore(task.taskId(), before, saveTask(task), event);
        });
    }

    @Override
    public RdTask saveWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        Objects.requireNonNull(task, "task must not be null");
        if (expectedStatus == null) {
            throw new IllegalArgumentException("expectedStatus must not be null");
        }
        return withinInMemoryStoreBoundary(() -> {
            RdTask before = taskStore.findTask(task.taskId()).orElse(null);
            taskStore.advanceStatusWithExpectedVersion(
                    task.taskId(), expectedVersion, expectedFencingToken, expectedStatus, task.status(),
                    errorMessage(task), executionResultJson(task), pullRequestUrl(task), promptSnapshot(task)
            );
            RdTask saved = taskStore.findTask(task.taskId()).orElseThrow(
                    () -> new IllegalStateException("task disappeared after fenced CAS: " + task.taskId()));
            return saveEventOrRestore(task.taskId(), before, saved, event);
        });
    }

    @Override
    public RdTask saveActionWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        Objects.requireNonNull(task, "task must not be null");
        if (expectedStatus == null) {
            throw new IllegalArgumentException("expectedStatus must not be null");
        }
        return withinInMemoryStoreBoundary(() -> {
            RdTask before = taskStore.findTask(task.taskId()).orElse(null);
            taskStore.updateTaskWithExpectedVersion(
                    task, expectedVersion, expectedFencingToken, expectedStatus);
            RdTask saved = taskStore.findTask(task.taskId()).orElseThrow(
                    () -> new IllegalStateException("task disappeared after fenced snapshot update: " + task.taskId()));
            return saveEventOrRestore(task.taskId(), before, saved, event);
        });
    }

    private RdTask saveTask(RdTask task) {
        return switch (task) {
            case RdBugFixTask bugFixTask -> taskStore.saveBugFixTask(bugFixTask);
            case RdRequirementTask requirementTask -> taskStore.saveRequirementTask(requirementTask);
            default -> throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
        };
    }

    private RdTask withinInMemoryStoreBoundary(java.util.function.Supplier<RdTask> mutation) {
        return inMemoryTaskStore == null ? mutation.get() : inMemoryTaskStore.executeAtomically(mutation);
    }

    private RdTask saveEventOrRestore(
            String taskId,
            RdTask before,
            RdTask saved,
            RdTaskStatusEvent event
    ) {
        if (eventStore != null && event != null) {
            try {
                eventStore.save(event);
            } catch (RuntimeException eventFailure) {
                if (inMemoryTaskStore != null) {
                    inMemoryTaskStore.restoreTaskSnapshot(taskId, before);
                }
                throw eventFailure;
            }
        }
        return saved;
    }

    private String errorMessage(RdTask task) {
        return task.errorMessage();
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
