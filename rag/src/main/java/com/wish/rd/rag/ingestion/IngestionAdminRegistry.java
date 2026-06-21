package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.knowledge.KnowledgeDocument;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.vector.InMemoryVectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class IngestionAdminRegistry {

    private final KnowledgeWorkspace workspace;
    private final LinkedHashMap<String, ManagedIngestionPipeline> pipelines = new LinkedHashMap<>();
    private final LinkedHashMap<String, ManagedIngestionTask> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<ManagedIngestionTaskNode>> taskNodes = new LinkedHashMap<>();
    private final AtomicLong pipelineSequence = new AtomicLong();
    private final AtomicLong taskSequence = new AtomicLong();

    private IngestionAdminRegistry(KnowledgeWorkspace workspace) {
        this.workspace = workspace;
        seedDefaultPipeline();
    }

    public static IngestionAdminRegistry inMemory(KnowledgeWorkspace workspace) {
        return new IngestionAdminRegistry(workspace);
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
        String taskId = "ingestion-task-" + taskSequence.incrementAndGet();
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
            tasks.put(taskId, task);
            taskNodes.put(taskId, toTaskNodes(task, pipeline, workspace.listDocumentLogs(document.id())));
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
            tasks.put(taskId, failed);
            taskNodes.put(taskId, List.of());
            return failed;
        }
    }

    public synchronized ManagedIngestionTask getTask(String taskId) {
        ManagedIngestionTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("ingestion task not found: " + taskId);
        }
        return task;
    }

    public synchronized List<ManagedIngestionTaskNode> listTaskNodes(String taskId) {
        getTask(taskId);
        return taskNodes.getOrDefault(taskId, List.of());
    }

    public synchronized IngestionTaskPage pageTasks(String status, int pageNo, int pageSize) {
        int safePageNo = pageNo <= 0 ? 1 : pageNo;
        int safePageSize = pageSize <= 0 ? 10 : pageSize;
        String normalizedStatus = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        List<ManagedIngestionTask> filtered = tasks.values().stream()
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
                    task.id() + "-node-" + (index + 1),
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
