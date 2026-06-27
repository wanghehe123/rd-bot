package com.wish.rd.bootstrap.controller.admin.knowledge;

import com.wish.rd.engine.admin.knowledge.KnowledgeAdminEngine;
import com.wish.rd.engine.admin.knowledge.KnowledgeAdminOverview;
import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.knowledge.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.FeishuDocImportCommand;
import com.wish.rd.rag.knowledge.FeishuDocKnowledgeImporter;
import com.wish.rd.rag.knowledge.KnowledgeBase;
import com.wish.rd.rag.knowledge.KnowledgeChunk;
import com.wish.rd.rag.knowledge.KnowledgeDocument;
import com.wish.rd.rag.knowledge.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.knowledge.WriteKnowledgeDocumentCommand;
import com.wish.rd.rag.ingestion.IngestionNodeLog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 REST 控制器。
 *
 * <p>对外暴露 /knowledge-base、/knowledge-base/docs、/knowledge-base/docs/{id}/chunks
 * 以及 /admin/overview 系列接口，覆盖知识库、文档、分块的增删改查、启停与批量操作，
 * 以及管理后台概览统计。作为 HTTP 适配层，业务逻辑下沉到
 * {@link KnowledgeAdminEngine} 与 {@link KnowledgeWorkspace}。
 */
@RestController
public final class KnowledgeAdminController {

    private final KnowledgeWorkspace workspace;
    private final KnowledgeAdminEngine adminEngine;
    private final FeishuDocKnowledgeImporter feishuImporter;

    public KnowledgeAdminController(
            KnowledgeWorkspace workspace,
            KnowledgeAdminEngine adminEngine,
            FeishuDocKnowledgeImporter feishuImporter
    ) {
        this.workspace = workspace;
        this.adminEngine = adminEngine;
        this.feishuImporter = feishuImporter;
    }

    @GetMapping("/knowledge-base")
    public PageResponse<KnowledgeBaseView> listKnowledgeBases(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "name", required = false) String name
    ) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        List<KnowledgeBaseView> all = workspace.searchBases(name).stream()
                .map(this::toKnowledgeBaseView)
                .toList();
        int total = all.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(all.subList(fromIndex, toIndex), total, pageSize, page, pages);
    }

    @PostMapping("/knowledge-base")
    public KnowledgeBaseView createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        return toKnowledgeBaseView(workspace.createBase(new CreateKnowledgeBaseCommand(request.name(), request.description())));
    }

    @GetMapping("/knowledge-base/{knowledgeBaseId}")
    public KnowledgeBaseView getKnowledgeBase(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        return toKnowledgeBaseView(workspace.getBase(knowledgeBaseId));
    }

    @PutMapping("/knowledge-base/{knowledgeBaseId}")
    public KnowledgeBaseView updateKnowledgeBase(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestBody KnowledgeBaseUpdateRequest request
    ) {
        return toKnowledgeBaseView(workspace.updateBase(knowledgeBaseId, request.name()));
    }

    @DeleteMapping("/knowledge-base/{knowledgeBaseId}")
    public DeleteResponse deleteKnowledgeBase(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        workspace.deleteBase(knowledgeBaseId);
        return new DeleteResponse(true);
    }

    @PostMapping("/knowledge-base/{knowledgeBaseId}/docs/write")
    public KnowledgeDocument writeDocument(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestBody WriteKnowledgeDocumentRequest request
    ) {
        return workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                knowledgeBaseId,
                request.sourceName(),
                request.knowledgeType(),
                request.mimeType(),
                request.contentBytes(),
                request.chunkingMode(),
                request.chunkSize(),
                request.overlapSize()
        ));
    }

    @PostMapping("/knowledge-base/{knowledgeBaseId}/docs/import/feishu")
    public KnowledgeDocument importFeishuDocument(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestBody FeishuImportRequest request
    ) {
        return feishuImporter.importDocument(new FeishuDocImportCommand(
                knowledgeBaseId,
                request.source(),
                request.knowledgeType(),
                request.chunkSize(),
                request.overlapSize()
        ));
    }

    @GetMapping("/knowledge-base/{knowledgeBaseId}/docs")
    public PageResponse<KnowledgeDocument> listDocuments(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        KnowledgeDocumentStatus statusFilter = parseStatus(status);
        List<KnowledgeDocument> all = workspace.searchDocuments(knowledgeBaseId, keyword, statusFilter);
        int total = all.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(all.subList(fromIndex, toIndex), total, pageSize, page, pages);
    }

    @GetMapping("/knowledge-base/docs/search")
    public List<KnowledgeDocument> searchDocuments(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", defaultValue = "8") int limit
    ) {
        return workspace.searchAllDocuments(keyword, limit);
    }

    @GetMapping("/knowledge-base/docs/{documentId}")
    public KnowledgeDocument getDocument(@PathVariable("documentId") String documentId) {
        return workspace.getDocument(documentId);
    }

    @PutMapping("/knowledge-base/docs/{documentId}")
    public KnowledgeDocument updateDocument(
            @PathVariable("documentId") String documentId,
            @RequestBody KnowledgeDocumentUpdateRequest request
    ) {
        return workspace.updateDocument(documentId, request.docName(), request.knowledgeType());
    }

    @DeleteMapping("/knowledge-base/docs/{documentId}")
    public DeleteResponse deleteDocument(@PathVariable("documentId") String documentId) {
        workspace.deleteDocument(documentId);
        return new DeleteResponse(true);
    }

    @GetMapping("/knowledge-base/docs/{documentId}/chunks")
    public PageResponse<KnowledgeChunk> listChunks(
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "enabled", required = false) Boolean enabled
    ) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        List<KnowledgeChunk> all = workspace.listChunks(documentId, enabled);
        int total = all.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(all.subList(fromIndex, toIndex), total, pageSize, page, pages);
    }

    @PostMapping("/knowledge-base/docs/{documentId}/chunks")
    public KnowledgeChunk createChunk(
            @PathVariable("documentId") String documentId,
            @RequestBody KnowledgeChunkCreateRequest request
    ) {
        int chunkIndex = request.index() == null ? 0 : request.index();
        return workspace.createChunk(documentId, request.chunkId(), chunkIndex, request.content());
    }

    @PutMapping("/knowledge-base/docs/{documentId}/chunks/{chunkId}")
    public KnowledgeChunk updateChunk(
            @PathVariable("documentId") String documentId,
            @PathVariable("chunkId") String chunkId,
            @RequestBody KnowledgeChunkUpdateRequest request
    ) {
        return workspace.updateChunk(documentId, chunkId, request.content());
    }

    @DeleteMapping("/knowledge-base/docs/{documentId}/chunks/{chunkId}")
    public DeleteResponse deleteChunk(
            @PathVariable("documentId") String documentId,
            @PathVariable("chunkId") String chunkId
    ) {
        return new DeleteResponse(workspace.deleteChunk(documentId, chunkId));
    }

    @GetMapping("/knowledge-base/docs/{documentId}/preview")
    public PreviewResponse preview(@PathVariable("documentId") String documentId) {
        return new PreviewResponse(workspace.previewDocument(documentId));
    }

    @GetMapping("/knowledge-base/docs/{documentId}/chunk-logs")
    public PageResponse<IngestionNodeLog> chunkLogs(
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        int page = Math.max(1, current);
        int pageSize = Math.max(1, size);
        List<IngestionNodeLog> all = workspace.listDocumentLogs(documentId);
        int total = all.size();
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(all.subList(fromIndex, toIndex), total, pageSize, page, pages);
    }

    @PatchMapping("/knowledge-base/docs/{documentId}/enabled")
    public KnowledgeDocument setDocumentEnabled(
            @PathVariable("documentId") String documentId,
            @RequestParam("enabled") boolean enabled
    ) {
        return workspace.setDocumentEnabled(documentId, enabled);
    }

    @PatchMapping("/knowledge-base/docs/{documentId}/enable")
    public KnowledgeDocument enableDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam("value") boolean enabled
    ) {
        return workspace.setDocumentEnabled(documentId, enabled);
    }

    @PatchMapping("/knowledge-base/docs/chunks/{chunkId}/enabled")
    public KnowledgeChunk setChunkEnabled(
            @PathVariable("chunkId") String chunkId,
            @RequestParam("enabled") boolean enabled
    ) {
        return workspace.setChunkEnabled(chunkId, enabled);
    }

    @PatchMapping("/knowledge-base/docs/{documentId}/chunks/{chunkId}/enable")
    public KnowledgeChunk enableChunk(
            @PathVariable("documentId") String documentId,
            @PathVariable("chunkId") String chunkId,
            @RequestParam("value") boolean enabled
    ) {
        workspace.batchSetChunksEnabled(documentId, List.of(chunkId), enabled);
        return workspace.getChunk(chunkId);
    }

    @PatchMapping("/knowledge-base/docs/{documentId}/chunks/batch-enable")
    public BatchUpdateResponse batchEnableChunks(
            @PathVariable("documentId") String documentId,
            @RequestParam("value") boolean enabled,
            @RequestBody(required = false) KnowledgeChunkBatchRequest request
    ) {
        List<String> chunkIds = request == null ? List.of() : request.chunkIds();
        return new BatchUpdateResponse(workspace.batchSetChunksEnabled(documentId, chunkIds, enabled));
    }

    /**
     * 列出可选的分块策略及其默认参数，供前端上传/编辑文档时构造分块配置。
     */
    @GetMapping("/knowledge-base/chunk-strategies")
    public List<ChunkStrategyOption> chunkStrategies() {
        return List.of(
                new ChunkStrategyOption(
                        ChunkingMode.FIXED_SIZE.name(),
                        "固定大小",
                        Map.of("chunkSize", 512, "overlapSize", 64)
                ),
                new ChunkStrategyOption(
                        ChunkingMode.STRUCTURE_AWARE.name(),
                        "语义感知（Markdown友好）",
                        Map.of("chunkSize", 1400, "overlapSize", 0)
                )
        );
    }

    /**
     * 触发文档重新分块：清除既有分块与向量后，以原文重新生成。
     */
    @PostMapping("/knowledge-base/docs/{documentId}/chunk")
    public KnowledgeDocument chunkDocument(
            @PathVariable("documentId") String documentId,
            @RequestBody(required = false) RechunkRequest request
    ) {
        ChunkingMode mode = request == null || request.chunkingMode() == null
                ? ChunkingMode.STRUCTURE_AWARE
                : request.chunkingMode();
        int chunkSize = request == null ? 0 : request.chunkSize();
        int overlapSize = request == null ? 0 : request.overlapSize();
        return workspace.rechunkDocument(documentId, mode, chunkSize, overlapSize);
    }

    /**
     * 直接在知识库下上传文档文件并立即分块索引，供知识库文档页使用。
     * 与 /ingestion/tasks/upload 并存，本端点不经过任务编排，直接写知识工作区。
     */
    @PostMapping(value = "/knowledge-base/{knowledgeBaseId}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeDocument uploadDocument(
            @PathVariable("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam(value = "knowledgeType", defaultValue = "document") String knowledgeType,
            @RequestParam(value = "chunkingMode", required = false) ChunkingMode chunkingMode,
            @RequestParam(value = "chunkSize", defaultValue = "512") int chunkSize,
            @RequestParam(value = "overlapSize", defaultValue = "0") int overlapSize,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("upload file must not be empty");
        }
        String sourceName = file.getOriginalFilename();
        if (sourceName == null || sourceName.isBlank()) {
            sourceName = "upload-" + System.currentTimeMillis();
        }
        WriteKnowledgeDocumentCommand command = new WriteKnowledgeDocumentCommand(
                knowledgeBaseId,
                sourceName,
                knowledgeType,
                file.getContentType(),
                file.getBytes(),
                chunkingMode == null ? ChunkingMode.STRUCTURE_AWARE : chunkingMode,
                chunkSize,
                overlapSize
        );
        return workspace.writeDocument(command, KnowledgeDocumentSource.local());
    }

    @GetMapping("/admin/overview")
    public KnowledgeAdminOverview overview() {
        return adminEngine.overview();
    }

    private KnowledgeBaseView toKnowledgeBaseView(KnowledgeBase base) {
        long documentCount = workspace.countDocuments(base.id());
        return new KnowledgeBaseView(
                base.id(),
                base.name(),
                base.description(),
                base.enabled(),
                base.createdAtEpochMillis(),
                documentCount,
                "in-memory",
                "rd_bot_" + base.id(),
                "local-user",
                String.valueOf(base.createdAtEpochMillis()),
                String.valueOf(base.createdAtEpochMillis())
        );
    }

    /** 解析文档状态过滤参数，空值或无法识别时返回 null（不过滤）。 */
    private KnowledgeDocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return KnowledgeDocumentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record CreateKnowledgeBaseRequest(String name, String description) {
    }

    public record KnowledgeBaseUpdateRequest(String name) {
    }

    public record FeishuImportRequest(
            String source,
            String knowledgeType,
            int chunkSize,
            int overlapSize
    ) {
    }

    public record KnowledgeBaseView(
            String id,
            String name,
            String description,
            boolean enabled,
            long createdAtEpochMillis,
            long documentCount,
            String embeddingModel,
            String collectionName,
            String createdBy,
            String createTime,
            String updateTime
    ) {
    }

    public record PageResponse<T>(List<T> records, int total, int size, int current, int pages) {
    }

    public record WriteKnowledgeDocumentRequest(
            String sourceName,
            String knowledgeType,
            String mimeType,
            String content,
            ChunkingMode chunkingMode,
            int chunkSize,
            int overlapSize
    ) {

        byte[] contentBytes() {
            String safeContent = content == null ? "" : content;
            return safeContent.getBytes(StandardCharsets.UTF_8);
        }
    }

    public record PreviewResponse(String content) {
    }

    public record KnowledgeDocumentUpdateRequest(String docName, String knowledgeType) {
    }

    public record KnowledgeChunkCreateRequest(String content, Integer index, String chunkId) {
    }

    public record KnowledgeChunkUpdateRequest(String content) {
    }

    public record KnowledgeChunkBatchRequest(List<String> chunkIds) {
    }

    public record DeleteResponse(boolean deleted) {
    }

    public record BatchUpdateResponse(int updated) {
    }

    /** 分块策略选项，附带默认参数供前端构造 chunkConfig。 */
    public record ChunkStrategyOption(String value, String label, Map<String, Integer> defaultConfig) {
    }

    /** 重新分块请求参数。 */
    public record RechunkRequest(ChunkingMode chunkingMode, Integer chunkSize, Integer overlapSize) {
    }
}
