package com.wish.rd.engine.requirement.policy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** RFC 8785 JCS canonicalization and lowercase SHA-256 digests for Host-owned JSON snapshots. */
public final class CanonicalJsonSha256 {
    private static final ObjectMapper I_JSON_READER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CanonicalJsonSha256() {
    }

    /** Returns the RFC 8785 canonical JSON digest in {@code sha256:<lowercase hex>} form. */
    public static String digest(String json) {
        String canonical = canonicalize(json);
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }

    /** Validates I-JSON before delegating byte-exact serialization to the vetted JCS library. */
    public static String canonicalize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON must not be blank");
        }
        try {
            JsonNode root = I_JSON_READER.readTree(json);
            if (root == null) {
                throw new IllegalArgumentException("JSON must contain one value");
            }
            rejectLoneSurrogates(root);
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException invalid) {
            throw new IllegalArgumentException("JSON must be valid I-JSON for RFC 8785 canonicalization", invalid);
        }
    }

    private static void rejectLoneSurrogates(JsonNode node) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(CanonicalJsonSha256::requireUnicodeScalarString);
        }
        if (node.isTextual()) {
            requireUnicodeScalarString(node.textValue());
        }
        node.elements().forEachRemaining(CanonicalJsonSha256::rejectLoneSurrogates);
    }

    private static void requireUnicodeScalarString(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isSurrogate(character)) {
                if (!Character.isHighSurrogate(character) || index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("I-JSON strings must not contain lone surrogate code points");
                }
                index++;
            }
        }
    }
}
