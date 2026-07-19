package com.wish.rd.rag.runtime.impl;

import com.wish.rd.rag.runtime.RdTaskStatePersistence;
import com.wish.rd.rag.runtime.RdTaskStatusEventStore;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;

import java.util.Objects;

/** Local/test task-state persistence composed from the existing snapshot and event stores. */
public final class CoordinatedRdTaskStatePersistence implements RdTaskStatePersistence {

    private final RdTaskStore taskStore;
    private final RdTaskStatusEventStore eventStore;

    public CoordinatedRdTaskStatePersistence(RdTaskStore taskStore, RdTaskStatusEventStore eventStore) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore must not be null");
        this.eventStore = eventStore;
    }

    @Override
    public RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event) {
        Objects.requireNonNull(task, "task must not be null");
        RdTask saved = switch (task) {
            case RdBugFixTask bugFixTask -> taskStore.saveBugFixTask(bugFixTask);
            case RdRequirementTask requirementTask -> taskStore.saveRequirementTask(requirementTask);
            default -> throw new IllegalArgumentException("unsupported rd task type: " + task.getClass().getName());
        };
        if (eventStore != null && event != null) {
            eventStore.save(event);
        }
        return saved;
    }
}
