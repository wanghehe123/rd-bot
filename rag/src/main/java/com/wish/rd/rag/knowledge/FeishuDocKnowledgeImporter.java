package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.knowledge.model.FeishuDocImportCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocumentSnapshot;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;

/**
 * Feishu 知识导入器：把 Feishu 文档快照写入知识库并触发分块索引。
 *
 * <p>同一知识库、同一 Feishu 来源、同 revision/checksum 的重复导入会返回已有文档，
 * 避免重复写入文档、分块和向量。
 */
@Component
public final class FeishuDocKnowledgeImporter {

    private final KnowledgeWorkspace workspace;
    private final FeishuDocumentClient documentClient;

    public FeishuDocKnowledgeImporter(KnowledgeWorkspace workspace, FeishuDocumentClient documentClient) {
        this.workspace = workspace;
        this.documentClient = documentClient;
    }

    /**
     * 导入 Feishu 文档。
     *
     * @param command 导入命令
     * @return 新建或已存在的知识文档
     */
    public KnowledgeDocument importDocument(FeishuDocImportCommand command) {
        FeishuDocumentSnapshot snapshot = documentClient.fetch(command.source());
        String sourceToken = snapshot.sourceToken().isBlank() ? extractToken(command.source()) : snapshot.sourceToken();
        String sourceUrl = snapshot.sourceUrl().isBlank() ? command.source() : snapshot.sourceUrl();
        return workspace.writeDocumentIfChanged(
                new WriteKnowledgeDocumentCommand(
                        command.knowledgeBaseId(),
                        snapshot.title(),
                        command.knowledgeType(),
                        "text/markdown",
                        snapshot.content().getBytes(StandardCharsets.UTF_8),
                        ChunkingMode.STRUCTURE_AWARE,
                        command.chunkSize(),
                        command.overlapSize()
                ),
                new KnowledgeDocumentSource(
                        "FEISHU",
                        sourceToken,
                        sourceUrl,
                        snapshot.revisionId(),
                        snapshot.fetchedAtEpochMillis(),
                        0L
                )
        );
    }

    private String extractToken(String source) {
        String trimmed = source == null ? "" : source.strip();
        if (trimmed.isBlank()) {
            return "";
        }
        String[] parts = trimmed.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("docx".equalsIgnoreCase(parts[i]) || "wiki".equalsIgnoreCase(parts[i])) {
                String token = parts[i + 1];
                int queryIndex = token.indexOf('?');
                return queryIndex > 0 ? token.substring(0, queryIndex) : token;
            }
        }
        return trimmed;
    }
}
