package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 重复来源身份分组中的一个可见文档。
 */
public record DuplicateIdentityMember(
        String documentId,
        long lastSyncedAtEpochMillis,
        long createdAtEpochMillis,
        long rowVersion
) {

    public DuplicateIdentityMember {
        Objects.requireNonNull(documentId, "documentId must not be null");
        lastSyncedAtEpochMillis = Math.max(0L, lastSyncedAtEpochMillis);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        rowVersion = Math.max(0L, rowVersion);
    }
}
