package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.ingestion.impl.InMemoryIngestionTaskStore;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.ingestion.model.IngestionNodeLog;
import com.wish.rd.rag.ingestion.model.IngestionNodeType;
import com.wish.rd.rag.ingestion.model.IngestionPipelineCommand;
import com.wish.rd.rag.ingestion.model.IngestionPipelineNodeCommand;
import com.wish.rd.rag.ingestion.model.IngestionPipelinePage;
import com.wish.rd.rag.ingestion.model.IngestionStatus;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.IngestionTaskPage;
import com.wish.rd.rag.ingestion.model.ManagedIngestionPipeline;
import com.wish.rd.rag.ingestion.model.ManagedIngestionPipelineNode;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTask;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.ManagedIngestionTaskNode;

@Component
public final class IngestionAdminRegistry {

    private final KnowledgeWorkspace workspace;
    private final IngestionTaskStore taskStore;
    private final SnowflakeIdGenerator idGenerator;
    private final LinkedHashMap<String, ManagedIngestionPipeline> pipelines = new LinkedHashMap<>();
    private final AtomicLong pipelineSequence = new AtomicLong();

    public IngestionAdminRegistry(
            KnowledgeWorkspace workspace,
            IngestionTaskStore taskStore,
            SnowflakeIdGenerator idGenerator
    ) {
        this.workspace = workspace;
        this.taskStore = taskStore;
        this.idGenerator = idGenerator;
        seedDefaultPipeline();
    }

    public static IngestionAdminRegistry inMemory(KnowledgeWorkspace workspace) {
        return new IngestionAdminRegistry(
                workspace,
                new InMemoryIngestionTaskStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
    }

    public static IngestionAdminRegistry withTaskStore(
            KnowledgeWorkspace workspace,
            IngestionTaskStore taskStore,
            SnowflakeIdGenerator idGenerator
    ) {
        return new IngestionAdminRegistry(workspace, taskStore, idGenerator);
    }

    public synchronized ManagedIngestionPipeline createPipeline(IngestionPipelineCommand command) {
        String id = "pipeline-" + pipelineSequence.incrementAndGet();
        ManagedIngestionPipeline pipeline = toPipeline(id, command, System.currentTimeMillis(), System.currentTimeMillis());
        validatePipeline(pipeline);
        pipelines.put(id, pipeline);
        return pipeline;
    }

    public synchronized ManagedIngestionPipeline updatePipeline(String id, IngestionPipelineCommand command) {
        ManagedIngestionPipeline existing = getPipeline(id);
        ManagedIngestionPipeline updated = toPipeline(
                id,
                command,
                existing.createTimeEpochMillis(),
                System.currentTimeMillis()
        );
        validatePipeline(updated);
        pipelines.put(id, updated);
        return updated;
    }

    public synchronized ManagedIngestionPipeline getPipeline(String id) {
        ManagedIngestionPipeline pipeline = pipelines.get(id);
        if (pipeline == null) {
            throw new IllegalArgumentException("ingestion pipeline not found: " + id);
        }
        return pipeline;
    }

    public synchronized IngestionPipelinePage pagePipelines(String keyword, int pageNo, int pageSize) {
        int safePageNo = pageNo <= 0 ? 1 : pageNo;
        int safePageSize = pageSize <= 0 ? 10 : pageSize;
        String normalizedKeyword = normalize(keyword);
        List<ManagedIngestionPipeline> filtered = pipelines.values().stream()
                .filter(pipeline -> normalizedKeyword.isBlank()
                        || normalize(pipeline.name()).contains(normalizedKeyword)
                        || normalize(pipeline.description()).contains(normalizedKeyword))
                .toList();
        int fromIndex = Math.min((safePageNo - 1) * safePageSize, filtered.size());
        int toIndex = Math.min(fromIndex + safePageSize, filtered.size());
        return new IngestionPipelinePage(filtered.subList(fromIndex, toIndex), filtered.size(), safePageNo, safePageSize);
    }

    public synchronized boolean deletePipeline(String id) {
        if ("default-document-pipeline".equals(id)) {
            throw new IllegalArgumentException("default ingestion pipeline cannot be deleted");
        }
        return pipelines.remove(id) != null;
    }

    public synchronized ManagedIngestionTask executeTask(ManagedIngestionTaskCommand command) {
        ManagedIngestionPipeline pipeline = getPipeline(command.pipelineId());
        String taskId = idGenerator.nextIdString();
        long startedAt = System.currentTimeMillis();
        try {
            KnowledgeDocument document = workspace.writeDocument(
                    pipeline.toPipelineDefinition(),
                    new IngestionTaskCommand(
                            taskId,
                            command.sourceFileName(),
                            command.knowledgeBaseId(),
                            command.knowledgeType(),
                            command.mimeType(),
                            command.content(),
                            command.chunkingMode(),
                            command.chunkSize(),
                            command.overlapSize()
                    )
            );
            long completedAt = System.currentTimeMillis();
            ManagedIngestionTask task = new ManagedIngestionTask(
                    taskId,
                    pipeline.id(),
                    command.knowledgeBaseId(),
                    document.id(),
                    command.sourceType(),
                    command.sourceLocation(),
                    command.sourceFileName(),
                    IngestionStatus.COMPLETED,
                    document.chunkCount(),
                    "",
                    command.metadata(),
                    startedAt,
                    completedAt,
                    "local",
                    startedAt,
                    completedAt
            );
            taskStore.saveTask(task);
            taskStore.saveTaskNodes(taskId, toTaskNodes(task, pipeline, workspace.listDocumentLogs(document.id())));
            return task;
        } catch (RuntimeException exception) {
            long completedAt = System.currentTimeMillis();
            ManagedIngestionTask failed = new ManagedIngestionTask(
                    taskId,
                    pipeline.id(),
                    command.knowledgeBaseId(),
                    "",
                    command.sourceType(),
                    command.sourceLocation(),
                    command.sourceFileName(),
                    IngestionStatus.FAILED,
                    0,
                    exception.getMessage(),
                    command.metadata(),
                    startedAt,
                    completedAt,
                    "local",
                    startedAt,
                    completedAt
            );
            taskStore.saveTask(failed);
            taskStore.saveTaskNodes(taskId, List.of());
            return failed;
        }
    }

    public synchronized ManagedIngestionTask getTask(String taskId) {
        return taskStore.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("ingestion task not found: " + taskId));
    }

    public synchronized List<ManagedIngestionTaskNode> listTaskNodes(String taskId) {
        getTask(taskId);
        return taskStore.listTaskNodes(taskId);
    }

    public synchronized IngestionTaskPage pageTasks(String status, int pageNo, int pageSize) {
        int safePageNo = pageNo <= 0 ? 1 : pageNo;
        int safePageSize = pageSize <= 0 ? 10 : pageSize;
        String normalizedStatus = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        List<ManagedIngestionTask> filtered = taskStore.listTasks().stream()
                .filter(task -> normalizedStatus.isBlank() || task.status().name().equals(normalizedStatus))
                .toList();
        int fromIndex = Math.min((safePageNo - 1) * safePageSize, filtered.size());
        int toIndex = Math.min(fromIndex + safePageSize, filtered.size());
        return new IngestionTaskPage(filtered.subList(fromIndex, toIndex), filtered.size(), safePageNo, safePageSize);
    }

    private ManagedIngestionPipeline toPipeline(
            String id,
            IngestionPipelineCommand command,
            long createTime,
            long updateTime
    ) {
        List<ManagedIngestionPipelineNode> nodes = new ArrayList<>();
        for (int index = 0; index < command.nodes().size(); index++) {
            IngestionPipelineNodeCommand node = command.nodes().get(index);
            IngestionNodeType.valueOf(node.nodeType());
            nodes.add(new ManagedIngestionPipelineNode(
                    id + "-node-" + (index + 1),
                    node.nodeId(),
                    node.nodeType(),
                    node.nextNodeId()
            ));
        }
        return new ManagedIngestionPipeline(id, command.name(), command.description(), "local", nodes, createTime, updateTime);
    }

    private List<ManagedIngestionTaskNode> toTaskNodes(
            ManagedIngestionTask task,
            ManagedIngestionPipeline pipeline,
            List<IngestionNodeLog> logs
    ) {
        List<ManagedIngestionTaskNode> nodes = new ArrayList<>();
        for (int index = 0; index < logs.size(); index++) {
            IngestionNodeLog log = logs.get(index);
            ManagedIngestionPipelineNode pipelineNode = index < pipeline.nodes().size() ? pipeline.nodes().get(index) : null;
            long now = System.currentTimeMillis();
            nodes.add(new ManagedIngestionTaskNode(
                    idGenerator.nextIdString(),
                    task.id(),
                    task.pipelineId(),
                    pipelineNode == null ? log.nodeType().name().toLowerCase(Locale.ROOT) : pipelineNode.nodeId(),
                    log.nodeType().name(),
                    index + 1,
                    log.success() ? "SUCCESS" : "FAILED",
                    log.durationMs(),
                    log.message(),
                    log.success() ? "" : log.message(),
                    Map.of("chunkCount", task.chunkCount()),
                    now,
                    now
            ));
        }
        return List.copyOf(nodes);
    }

    private void validatePipeline(ManagedIngestionPipeline pipeline) {
        TaskIngestionEngine.inMemory(new InMemoryVectorStore()).execute(
                pipeline.toPipelineDefinition(),
                new IngestionTaskCommand(
                        "validation-task",
                        "validation.txt",
                        "validation-kb",
                        "validation",
                        "text/plain",
                        "validation".getBytes(),
                        null,
                        512,
                        0
                )
        );
    }

    private void seedDefaultPipeline() {
        long now = System.currentTimeMillis();
        ManagedIngestionPipeline pipeline = toPipeline(
                "default-document-pipeline",
                new IngestionPipelineCommand("默认文档摄取管道", "Fetcher -> Parser -> Chunker -> Indexer", IngestionPipelineCommand.defaultNodes()),
                now,
                now
        );
        pipelines.put(pipeline.id(), pipeline);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
