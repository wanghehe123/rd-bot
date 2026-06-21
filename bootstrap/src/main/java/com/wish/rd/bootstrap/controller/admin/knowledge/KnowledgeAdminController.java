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
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.knowledge.WriteKnowledgeDocumentCommand;
import com.wish.rd.rag.ingestion.IngestionNodeLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
    public KnowledgeBase createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        return workspace.createBase(new CreateKnowledgeBaseCommand(request.name(), request.description()));
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
    public List<KnowledgeDocument> listDocuments(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        return workspace.listDocuments(knowledgeBaseId);
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
    public List<KnowledgeChunk> listChunks(@PathVariable("documentId") String documentId) {
        return workspace.listChunks(documentId);
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
    public List<IngestionNodeLog> chunkLogs(@PathVariable("documentId") String documentId) {
        return workspace.listDocumentLogs(documentId);
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
}
