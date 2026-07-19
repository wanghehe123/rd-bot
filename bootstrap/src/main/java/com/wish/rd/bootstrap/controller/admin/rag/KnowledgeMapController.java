package com.wish.rd.bootstrap.controller.admin.rag;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeBaseRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeChunkRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeDocumentRow;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeMapNodeRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeBaseMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeChunkMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeDocumentMapper;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeMapNodeMapper;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import javax.sql.DataSource;

/**
 * Read-only knowledge map API used by controlled retrieval planning. It exposes document and
 * chunk summaries rather than raw content, so directory exploration cannot expand an arbitrary
 * document into a prompt.
 */
@RestController
@ConditionalOnBean(DataSource.class)
public class KnowledgeMapController {

    private static final int SUMMARY_LIMIT = 480;

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeMapNodeMapper mapNodeMapper;
    private final SnowflakeIdGenerator idGenerator;

    public KnowledgeMapController(
            KnowledgeBaseMapper baseMapper,
            KnowledgeDocumentMapper documentMapper,
            KnowledgeChunkMapper chunkMapper,
            KnowledgeMapNodeMapper mapNodeMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.baseMapper = baseMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.mapNodeMapper = mapNodeMapper;
        this.idGenerator = idGenerator;
    }

    @GetMapping("/admin/knowledge-base/{knowledgeBaseId}/map")
    public List<KnowledgeMapNodeView> map(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        long knowledgeBase = requiredKnowledgeBaseId(knowledgeBaseId);
        return mapNodeMapper.selectList(new QueryWrapper<KnowledgeMapNodeRow>()
                        .eq("knowledge_base_id", knowledgeBase)
                        .orderByAsc("path", "id"))
                .stream()
                .map(KnowledgeMapNodeView::from)
                .toList();
    }

    @PostMapping("/admin/knowledge-base/{knowledgeBaseId}/map/rebuild")
    @Transactional
    public KnowledgeMapRebuildResponse rebuild(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        long knowledgeBase = requiredKnowledgeBaseId(knowledgeBaseId);
        mapNodeMapper.deleteByKnowledgeBaseId(knowledgeBase);

        long rootId = nextId();
        mapNodeMapper.insertNode(node(
                rootId, knowledgeBase, null, "KB", "/", "知识库 " + knowledgeBase, "受控知识地图根节点",
                null, null, null, "", "", 0, true
        ));

        List<KnowledgeDocumentRow> documents = documentMapper.selectList(new QueryWrapper<KnowledgeDocumentRow>()
                .eq("knowledge_base_id", knowledgeBase)
                .orderByAsc("id"));
        int nodeCount = 1;
        for (KnowledgeDocumentRow document : documents) {
            long documentId = document.id;
            String path = "/documents/" + documentId;
            int chunkCount = document.chunkCount == null ? 0 : Math.max(0, document.chunkCount);
            long documentNodeId = nextId();
            mapNodeMapper.insertNode(node(
                    documentNodeId, knowledgeBase, rootId, "DOCUMENT", path,
                    safeDocumentTitle(document.sourceName, documentId),
                    "文档节点 #" + documentId, documentId, 0, Math.max(0, chunkCount - 1), document.revisionId,
                    document.checksum, Math.max(0, chunkCount) * 64, Boolean.TRUE.equals(document.enabled)
            ));
            nodeCount++;

            List<KnowledgeChunkRow> chunks = chunkMapper.selectList(new QueryWrapper<KnowledgeChunkRow>()
                    .eq("knowledge_base_id", knowledgeBase)
                    .eq("document_id", documentId)
                    .orderByAsc("chunk_index", "id"));
            for (KnowledgeChunkRow chunk : chunks) {
                int index = chunk.chunkIndex == null ? 0 : Math.max(0, chunk.chunkIndex);
                mapNodeMapper.insertNode(node(
                        nextId(), knowledgeBase, documentNodeId, "CHUNK_RANGE", path + "/chunks/" + index,
                        "Chunk #" + index, "Chunk 节点 #" + index, documentId, index, index, document.revisionId,
                        chunk.contentHash, 64, Boolean.TRUE.equals(chunk.enabled)
                ));
                nodeCount++;
            }
        }
        return new KnowledgeMapRebuildResponse(String.valueOf(knowledgeBase), nodeCount);
    }

    private long requiredKnowledgeBaseId(String rawId) {
        final long knowledgeBaseId;
        try {
            knowledgeBaseId = Long.parseLong(rawId);
        } catch (NumberFormatException exception) {
            throw new KnowledgeMapNotFoundException();
        }
        KnowledgeBaseRow base = baseMapper.selectById(knowledgeBaseId);
        if (base == null) {
            throw new KnowledgeMapNotFoundException();
        }
        return knowledgeBaseId;
    }

    private KnowledgeMapNodeRow node(
            long id, long knowledgeBaseId, Long parentId, String nodeType, String path, String title, String summary,
            Long documentId, Integer chunkStart, Integer chunkEnd, String revision, String checksum,
            int tokenEstimate, boolean enabled
    ) {
        KnowledgeMapNodeRow row = new KnowledgeMapNodeRow();
        row.id = id;
        row.knowledgeBaseId = knowledgeBaseId;
        row.parentId = parentId;
        row.nodeType = nodeType;
        row.path = path;
        row.title = text(title);
        row.summary = text(summary);
        row.documentId = documentId;
        row.chunkStart = chunkStart;
        row.chunkEnd = chunkEnd;
        row.revisionId = text(revision);
        row.checksum = text(checksum);
        row.tokenEstimate = Math.max(0, tokenEstimate);
        row.metadataJson = "{}";
        row.enabled = enabled;
        return row;
    }

    private long nextId() {
        return Long.parseLong(idGenerator.nextIdString());
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String safeDocumentTitle(String sourceName, long documentId) {
        String raw = text(sourceName);
        if (raw.isBlank()) {
            return "文档 #" + documentId;
        }
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String base = slash >= 0 && slash + 1 < raw.length() ? raw.substring(slash + 1) : raw;
        return base.isBlank() ? "文档 #" + documentId : base;
    }

    private static String summary(String source) {
        String compact = text(source).replaceAll("\\s+", " ");
        return compact.length() <= SUMMARY_LIMIT ? compact : compact.substring(0, SUMMARY_LIMIT);
    }

    private static int estimateTokens(String source) {
        return Math.max(0, (text(source).length() + 3) / 4);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class KnowledgeMapNotFoundException extends RuntimeException {
    }

    public record KnowledgeMapNodeView(
            String id, String parentId, String nodeType, String path, String title, String summary,
            Long documentId, Integer chunkStart, Integer chunkEnd, String revisionId, String checksum,
            int tokenEstimate, boolean enabled
    ) {
        static KnowledgeMapNodeView from(KnowledgeMapNodeRow row) {
            return new KnowledgeMapNodeView(
                    String.valueOf(row.id),
                    row.parentId == null ? null : String.valueOf(row.parentId),
                    row.nodeType, row.path, row.title, row.summary,
                    row.documentId, row.chunkStart, row.chunkEnd, row.revisionId, row.checksum,
                    row.tokenEstimate == null ? 0 : row.tokenEstimate, Boolean.TRUE.equals(row.enabled)
            );
        }
    }

    public record KnowledgeMapRebuildResponse(String knowledgeBaseId, int nodeCount) {
    }
}
