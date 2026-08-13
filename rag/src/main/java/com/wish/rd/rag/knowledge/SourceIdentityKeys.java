package com.wish.rd.rag.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;

/**
 * 外部来源稳定身份。优先 {@code sourceType + sourceToken}，否则规范化 URL。
 * 本地匿名上传没有身份键，每次写入都是新逻辑文档。
 */
public final class SourceIdentityKeys {

    private SourceIdentityKeys() {
    }

    public static String from(KnowledgeDocumentSource source) {
        if (source == null) {
            return "";
        }
        return from(source.sourceType(), source.sourceToken(), source.sourceUrl());
    }

    public static String from(String sourceType, String sourceToken, String sourceUrl) {
        String type = sourceType == null || sourceType.isBlank()
                ? "LOCAL"
                : sourceType.strip().toUpperCase(Locale.ROOT);
        String token = sourceToken == null ? "" : sourceToken.strip();
        String url = sourceUrl == null ? "" : sourceUrl.strip();
        String canonical;
        if (!token.isBlank()) {
            canonical = type + "\0token\0" + token;
        } else if (!url.isBlank()) {
            canonical = type + "\0url\0" + url;
        } else {
            return "";
        }
        return sha256(canonical);
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
