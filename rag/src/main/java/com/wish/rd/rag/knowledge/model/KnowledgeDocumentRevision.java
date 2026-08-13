package com.wish.rd.rag.knowledge.model;

import java.util.Objects;

/**
 * 逻辑文档的不可变规范正文 revision。相同 checksum 必须复用，不得递增 {@code sync_version}。
 */
public record KnowledgeDocumentRevision(
        String id,
        String documentId,
        long syncVersion,
        String sourceRevisionId,
        String checksum,
        String canonicalMimeType,
        String canonicalContent,
        String parserName,
        String parserVersion,
        long createdAtEpochMillis
) {

    public KnowledgeDocumentRevision {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (syncVersion <= 0L) {
            throw new IllegalArgumentException("syncVersion must be positive");
        }
        sourceRevisionId = sourceRevisionId == null ? "" : sourceRevisionId;
        checksum = checksum == null ? "" : checksum;
        canonicalMimeType = canonicalMimeType == null || canonicalMimeType.isBlank()
                ? "text/markdown"
                : canonicalMimeType;
        canonicalContent = canonicalContent == null ? "" : canonicalContent;
        parserName = parserName == null || parserName.isBlank() ? "rd-bot-default" : parserName;
        parserVersion = parserVersion == null || parserVersion.isBlank() ? "1" : parserVersion;
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
    }
}
