package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 操作员确认取代时回传的被取代文档及其期望行版本。
 */
public record SupersedeTarget(String documentId, long expectedRowVersion) {

    public SupersedeTarget {
        Objects.requireNonNull(documentId, "documentId must not be null");
        expectedRowVersion = Math.max(0L, expectedRowVersion);
    }
}
