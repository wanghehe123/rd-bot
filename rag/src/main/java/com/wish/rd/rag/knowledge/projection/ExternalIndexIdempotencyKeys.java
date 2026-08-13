package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Outbox 幂等键：provider + scope + scopeId + version + operation。
 */
public final class ExternalIndexIdempotencyKeys {

    private ExternalIndexIdempotencyKeys() {
    }

    public static String document(
            String provider,
            String documentId,
            long syncVersion,
            ExternalKnowledgeOperationType operationType
    ) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(operationType, "operationType must not be null");
        return sha256(normalize(provider) + "\0document\0" + documentId + "\0" + syncVersion + "\0" + operationType.name());
    }

    public static String knowledgeBase(
            String provider,
            String knowledgeBaseId,
            long syncVersion,
            ExternalKnowledgeOperationType operationType
    ) {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(operationType, "operationType must not be null");
        return sha256(normalize(provider) + "\0kb\0" + knowledgeBaseId + "\0" + syncVersion + "\0" + operationType.name());
    }

    private static String normalize(String provider) {
        return provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
