package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;

/**
 * 一篇文档的投影详情：绑定、操作时间线、最近错误。
 *
 * @param binding          投影绑定
 * @param operations       该文档的 outbox 时间线，新的在前
 * @param lastErrorCode    最近错误码
 * @param lastErrorMessage 已脱敏的最近错误
 */
public record ProjectionAdminDocumentDetail(
        KnowledgeExternalIndexBinding binding,
        List<KnowledgeExternalIndexOperation> operations,
        String lastErrorCode,
        String lastErrorMessage
) {

    public ProjectionAdminDocumentDetail {
        operations = operations == null ? List.of() : List.copyOf(operations);
        lastErrorCode = lastErrorCode == null ? "" : lastErrorCode;
        lastErrorMessage = lastErrorMessage == null ? "" : lastErrorMessage;
    }
}
