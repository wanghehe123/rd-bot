package com.wish.rd.rag.knowledge.projection.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次外部索引 upsert 的完整意图。正文取自冻结 revision，不读可能已变化的当前文档。
 *
 * @param provider         外部索引提供方
 * @param knowledgeBaseId  知识库数字 ID
 * @param documentId       逻辑文档数字 ID
 * @param syncVersion      期望投影版本
 * @param checksum         规范正文 SHA-256
 * @param resourceRootUri  add_resource 的目标资源根，返回 root_uri 必须与之精确相等
 * @param fileName         上传文件名，固定 {@code source.md}
 * @param canonicalContent 冻结的规范正文
 * @param ownershipMarker  {@code rd-bot:{kbId}:{docId}}
 * @param tags             写入远端的 ownership/version tags
 * @param idempotencyKey   Outbox 幂等键，用于关联远端提交与本地 operation
 */
public record ExternalKnowledgeUpsertCommand(
        String provider,
        String knowledgeBaseId,
        String documentId,
        long syncVersion,
        String checksum,
        String resourceRootUri,
        String fileName,
        String canonicalContent,
        String ownershipMarker,
        List<String> tags,
        String idempotencyKey
) {

    public ExternalKnowledgeUpsertCommand {
        provider = provider == null || provider.isBlank()
                ? KnowledgeExternalIndexBinding.OPENVIKING
                : provider.strip().toUpperCase();
        knowledgeBaseId = requireNonBlank("knowledgeBaseId", knowledgeBaseId);
        documentId = requireNonBlank("documentId", documentId);
        if (syncVersion <= 0L) {
            throw new IllegalArgumentException("syncVersion must be positive");
        }
        checksum = requireNonBlank("checksum", checksum);
        resourceRootUri = requireNonBlank("resourceRootUri", resourceRootUri);
        fileName = requireNonBlank("fileName", fileName);
        canonicalContent = Objects.requireNonNullElse(canonicalContent, "");
        ownershipMarker = requireNonBlank("ownershipMarker", ownershipMarker);
        tags = tags == null ? List.of() : List.copyOf(tags);
        idempotencyKey = requireNonBlank("idempotencyKey", idempotencyKey);
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
