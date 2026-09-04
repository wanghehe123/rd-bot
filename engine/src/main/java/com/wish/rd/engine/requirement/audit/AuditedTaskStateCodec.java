package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

import java.nio.charset.StandardCharsets;

/** Canonical JSON + SHA-256 codec for {@link AuditedTaskState}. */
public final class AuditedTaskStateCodec {

    public static final int MAX_CANONICAL_BYTES = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Returns {@code state} with {@code stateHash} set to the SHA-256 of its canonical payload.
     *
     * @param state unsigned or previously sealed state
     * @return sealed state
     */
    public AuditedTaskState seal(AuditedTaskState state) {
        AuditedTaskState unsigned = unsigned(require(state));
        String hash = CanonicalJsonSha256.digest(canonicalPayload(unsigned));
        AuditedTaskState sealed = unsigned.withRevision(
                unsigned.stateVersion(), hash, unsigned.records(), unsigned.lastAuditRunId());
        encodeSealed(sealed);
        return sealed;
    }

    /**
     * Canonical JSON of the sealed state, including {@code stateHash}.
     *
     * @param state unsigned or sealed state
     * @return RFC 8785 canonical JSON
     */
    public String encodeCanonical(AuditedTaskState state) {
        AuditedTaskState required = require(state);
        AuditedTaskState sealed = required.stateHash().isBlank() ? seal(required) : required;
        return encodeSealed(sealed);
    }

    /**
     * Canonical JSON used to compute {@code stateHash} (excludes the hash field).
     *
     * @param state any valid state
     * @return RFC 8785 canonical payload
     */
    public String canonicalPayload(AuditedTaskState state) {
        try {
            JsonNode tree = MAPPER.valueToTree(require(state));
            if (tree instanceof ObjectNode object) {
                object.remove("stateHash");
            }
            return CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(tree));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("audited state cannot be encoded", invalid);
        }
    }

    /**
     * Decodes canonical JSON and verifies the embedded hash.
     *
     * @param json stored JSON
     * @return sealed state
     */
    public AuditedTaskState decode(String json) {
        String canonical = CanonicalJsonSha256.canonicalize(json);
        requireSize(canonical);
        try {
            AuditedTaskState decoded = MAPPER.readValue(canonical, AuditedTaskState.class);
            AuditedTaskState sealed = seal(unsigned(decoded));
            if (!sealed.stateHash().equals(decoded.stateHash())) {
                throw new IllegalArgumentException("audited state hash mismatch");
            }
            return sealed;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid audited state JSON", invalid);
        }
    }

    private String encodeSealed(AuditedTaskState sealed) {
        try {
            String json = CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(sealed));
            requireSize(json);
            String expected = CanonicalJsonSha256.digest(canonicalPayload(sealed));
            if (!expected.equals(sealed.stateHash())) {
                throw new IllegalArgumentException("audited state hash mismatch");
            }
            return json;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("audited state cannot be encoded", invalid);
        }
    }

    private static AuditedTaskState unsigned(AuditedTaskState state) {
        return state.withRevision(state.stateVersion(), "", state.records(), state.lastAuditRunId());
    }

    private static AuditedTaskState require(AuditedTaskState state) {
        if (state == null) {
            throw new IllegalArgumentException("audited state must not be null");
        }
        return state;
    }

    private static void requireSize(String json) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException(
                    "audited state canonical JSON exceeds 256 KiB: " + bytes);
        }
    }
}
