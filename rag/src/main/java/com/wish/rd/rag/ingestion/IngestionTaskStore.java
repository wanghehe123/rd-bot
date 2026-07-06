package com.wish.rd.rag.ingestion;

import java.util.List;
import java.util.Optional;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTask;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTaskNode;

/**
 * 摄取任务存储端口：持久化任务主记录和节点日志。
 */
public interface IngestionTaskStore {

    void saveTask(ManagedIngestionTask task);

    void saveTaskNodes(String taskId, List<ManagedIngestionTaskNode> nodes);

    Optional<ManagedIngestionTask> findTask(String taskId);

    List<ManagedIngestionTaskNode> listTaskNodes(String taskId);

    List<ManagedIngestionTask> listTasks();
}
