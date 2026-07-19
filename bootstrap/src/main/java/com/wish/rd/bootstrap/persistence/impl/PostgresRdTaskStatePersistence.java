package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.runtime.RdTaskStatePersistence;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
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
}
