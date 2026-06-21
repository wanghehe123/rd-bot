package com.wish.rd.rag.ingestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存摄取任务 Store。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryIngestionTaskStore implements IngestionTaskStore {

    private final LinkedHashMap<String, ManagedIngestionTask> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManagedIngestionTaskNode>> taskNodes = new LinkedHashMap<>();

    @Override
    public synchronized void saveTask(ManagedIngestionTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public synchronized void saveTaskNodes(String taskId, List<ManagedIngestionTaskNode> nodes) {
        taskNodes.put(taskId, nodes == null ? List.of() : List.copyOf(nodes));
    }

    @Override
    public synchronized Optional<ManagedIngestionTask> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized List<ManagedIngestionTaskNode> listTaskNodes(String taskId) {
        return taskNodes.getOrDefault(taskId, List.of());
    }

    @Override
    public synchronized List<ManagedIngestionTask> listTasks() {
        return List.copyOf(tasks.values());
    }
}
