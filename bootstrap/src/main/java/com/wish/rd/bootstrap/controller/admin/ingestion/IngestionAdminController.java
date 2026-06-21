package com.wish.rd.bootstrap.controller.admin.ingestion;

import com.wish.rd.engine.admin.ingestion.IngestionAdminEngine;
import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.ingestion.IngestionPipelineCommand;
import com.wish.rd.rag.ingestion.IngestionPipelineNodeCommand;
import com.wish.rd.rag.ingestion.ManagedIngestionTaskCommand;
import com.wish.rd.rag.ingestion.ObjectStorageService;
import com.wish.rd.rag.ingestion.StoredIngestionFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄取管理 REST 控制器。
 *
 * <p>对外暴露 /ingestion/pipelines、/ingestion/tasks 及 /ingestion/tasks/upload 系列
 * 接口，覆盖管线的增删改查与分页、任务的创建/查询/节点列表，以及多部分文件上传
 * （上传时先经由 {@link ObjectStorageService} 落对象存储）。HTTP 适配层，
 * 业务编排下沉到 {@link IngestionAdminEngine}。
 */
@RestController
public final class IngestionAdminController {

    private final IngestionAdminEngine adminEngine;
    private final ObjectStorageService objectStorageService;
    private final String bucketName;

    public IngestionAdminController(
            IngestionAdminEngine adminEngine,
            ObjectStorageService objectStorageService,
            @Value("${rustfs.bucket:biz}") String bucketName
    ) {
        this.adminEngine = adminEngine;
        this.objectStorageService = objectStorageService;
        this.bucketName = bucketName;
    }

    @PostMapping("/ingestion/pipelines")
    public Object createPipeline(@RequestBody IngestionPipelineRequest request) {
        return adminEngine.createPipeline(request.toCommand());
    }

    @PutMapping("/ingestion/pipelines/{id}")
    public Object updatePipeline(
            @PathVariable("id") String id,
            @RequestBody IngestionPipelineRequest request
    ) {
        return adminEngine.updatePipeline(id, request.toCommand());
    }

    @GetMapping("/ingestion/pipelines/{id}")
    public Object getPipeline(@PathVariable("id") String id) {
        return adminEngine.getPipeline(id);
    }

    @GetMapping("/ingestion/pipelines")
    public Object pagePipelines(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return adminEngine.pagePipelines(keyword, pageNo, pageSize);
    }

    @DeleteMapping("/ingestion/pipelines/{id}")
    public Object deletePipeline(@PathVariable("id") String id) {
        return Map.of("deleted", adminEngine.deletePipeline(id));
    }

    @PostMapping("/ingestion/tasks")
    public Object createTask(@RequestBody IngestionTaskCreateRequest request) {
        return adminEngine.executeTask(request.toCommand());
    }

    @PostMapping(value = "/ingestion/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object uploadTask(
            @RequestParam("pipelineId") String pipelineId,
            @RequestParam("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "knowledgeType", defaultValue = "document") String knowledgeType,
            @RequestParam(value = "chunkingMode", required = false) ChunkingMode chunkingMode,
            @RequestParam(value = "chunkSize", defaultValue = "512") int chunkSize,
            @RequestParam(value = "overlapSize", defaultValue = "0") int overlapSize,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("upload file must not be empty");
        }
        StoredIngestionFile storedFile;
        try (InputStream inputStream = file.getInputStream()) {
            storedFile = objectStorageService.upload(
                    bucketName,
                    inputStream,
                    file.getSize(),
                    file.getOriginalFilename(),
                    file.getContentType()
            );
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("storageUrl", storedFile.url());
        metadata.put("storageType", "rustfs-s3");
        metadata.put("detectedType", storedFile.detectedType());
        metadata.put("storedFileSize", storedFile.size());
        metadata.put("originalFilename", storedFile.originalFilename());

        return adminEngine.executeTask(new ManagedIngestionTaskCommand(
                pipelineId,
                knowledgeBaseId,
                knowledgeType,
                storedFile.detectedType().isBlank() ? file.getContentType() : storedFile.detectedType(),
                storedFile.originalFilename(),
                "s3",
                storedFile.url(),
                file.getBytes(),
                chunkingMode,
                chunkSize,
                overlapSize,
                metadata
        ));
    }

    @GetMapping("/ingestion/tasks/{id}")
    public Object getTask(@PathVariable("id") String id) {
        return adminEngine.getTask(id);
    }

    @GetMapping("/ingestion/tasks/{id}/nodes")
    public Object taskNodes(@PathVariable("id") String id) {
        return adminEngine.listTaskNodes(id);
    }

    @GetMapping("/ingestion/tasks")
    public Object pageTasks(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String status
    ) {
        return adminEngine.pageTasks(status, pageNo, pageSize);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }

    public record IngestionPipelineRequest(
            String name,
            String description,
            List<IngestionPipelineNodeRequest> nodes
    ) {

        IngestionPipelineCommand toCommand() {
            List<IngestionPipelineNodeCommand> nodeCommands = nodes == null
                    ? List.of()
                    : nodes.stream().map(IngestionPipelineNodeRequest::toCommand).toList();
            return new IngestionPipelineCommand(name, description, nodeCommands);
        }
    }

    public record IngestionPipelineNodeRequest(
            String nodeId,
            String nodeType,
            String nextNodeId
    ) {

        IngestionPipelineNodeCommand toCommand() {
            return new IngestionPipelineNodeCommand(nodeId, nodeType, nextNodeId);
        }
    }

    public record IngestionTaskCreateRequest(
            String pipelineId,
            String knowledgeBaseId,
            String knowledgeType,
            String mimeType,
            DocumentSourceRequest source,
            ChunkingMode chunkingMode,
            int chunkSize,
            int overlapSize,
            Map<String, Object> metadata
    ) {

        ManagedIngestionTaskCommand toCommand() {
            DocumentSourceRequest safeSource = source == null ? DocumentSourceRequest.empty() : source;
            return new ManagedIngestionTaskCommand(
                    pipelineId,
                    knowledgeBaseId,
                    knowledgeType,
                    mimeType,
                    safeSource.fileName(),
                    safeSource.type(),
                    safeSource.location(),
                    safeSource.contentBytes(),
                    chunkingMode,
                    chunkSize,
                    overlapSize,
                    metadata
            );
        }
    }

    public record DocumentSourceRequest(
            String type,
            String location,
            String fileName,
            String content
    ) {

        static DocumentSourceRequest empty() {
            return new DocumentSourceRequest("inline", "", "inline.txt", "");
        }

        byte[] contentBytes() {
            String safeContent = content == null ? "" : content;
            return safeContent.getBytes(StandardCharsets.UTF_8);
        }
    }
}
