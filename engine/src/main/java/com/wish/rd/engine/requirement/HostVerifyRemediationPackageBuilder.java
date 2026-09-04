package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** Builds the only Host-controlled HOST_VERIFY_FIX attachment. */
public final class HostVerifyRemediationPackageBuilder {
    public static final String ATTACHMENT_PATH = "attachments/host-verify-remediation/request.json";
    public static final String CONTAINER_PATH = "/work/input/attachments/host-verify-remediation/request.json";
    public static final String PROTOCOL = "rd-host-verify-remediation-request/v1";
    private static final int MAX_JSON_BYTES = 65_536;
    private static final int MAX_TEXT_CHARS = 4_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(bearer\\s+\\S+|api[_-]?key\\s*[:=]|secret\\s*[:=]|password\\s*[:=]|sk-[a-z0-9_-]{8,})");
    private static final Pattern EXTERNAL_URL = Pattern.compile("(?i)https?://|s3://|file://");

    /** Rehydrates an already sanitized immutable ledger request without consulting live verify state. */
    public Package fromFrozen(String requestJson, String requestHash) {
        String hash = requireText(requestHash, "requestHash").toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("frozen host-verify remediation request identity is invalid");
        }
        String canonical;
        try {
            canonical = CanonicalJsonSha256.requireCanonicalMatchingHash(
                    requireText(requestJson, "requestJson"), hash);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("frozen host-verify remediation request identity is invalid", invalid);
        }
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("frozen host-verify remediation request identity is invalid");
        }
        try {
            JsonNode root = MAPPER.readTree(canonical);
            if (!PROTOCOL.equals(root.path("protocol").asText())) {
                throw new IllegalArgumentException("unsupported frozen host-verify remediation protocol");
            }
            String runId = requireText(root.path("hostVerificationRunId").asText(), "hostVerificationRunId");
            String codingStage = requireText(root.path("codingStageRunId").asText(), "codingStageRunId");
            int remediationNo = root.path("remediationNo").asInt(0);
            if (remediationNo < 1 || remediationNo > 2) {
                throw new IllegalArgumentException("remediationNo must be between 1 and 2");
            }
            String category = safeText(root.path("failureCategory").asText(), "failureCategory");
            String error = safeText(root.path("errorMessage").asText(), "errorMessage");
            String reason = safeText(root.path("reason").asText(), "reason");
            RequirementExecutionRequest.InitialAgentStateAttachment attachment =
                    new RequirementExecutionRequest.InitialAgentStateAttachment(
                            ATTACHMENT_PATH, canonical, hash, bytes.length);
            String prompt = "HOST_VERIFY_FIX round " + remediationNo + " for coding stage " + codingStage
                    + " (run " + runId + ").\n"
                    + "Authoritative request: " + CONTAINER_PATH + " (" + hash + ")\n"
                    + "Category: " + category + "\nReason: " + reason + "\nFailure: " + error;
            return new Package(attachment, prompt, List.of(
                    "修复 HOST_VERIFY " + category + " 并保证下一轮宿主 BUILD/STATIC 通过"), hash);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid frozen host-verify remediation request JSON", invalid);
        }
    }

    /**
     * Builds a bounded, sanitized, hash-bound HOST_VERIFY_FIX request.
     *
     * @param hostVerificationRunId failed verification run
     * @param codingStageRunId coding snapshot that failed host verify
     * @param remediationNo 1-based round
     * @param failureCategory machine category, typically {@code PRODUCT_DEFECT}
     * @param errorMessage operator-facing failure detail
     * @return package for prompt + ledger
     */
    public Package build(
            String hostVerificationRunId,
            String codingStageRunId,
            int remediationNo,
            String failureCategory,
            String errorMessage
    ) {
        if (remediationNo < 1 || remediationNo > 2) {
            throw new IllegalArgumentException("remediationNo must be between 1 and 2");
        }
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("protocol", PROTOCOL);
            payload.put("hostVerificationRunId", requireText(hostVerificationRunId, "hostVerificationRunId"));
            payload.put("codingStageRunId", requireText(codingStageRunId, "codingStageRunId"));
            payload.put("remediationNo", remediationNo);
            payload.put("failureCategory", safeText(failureCategory, "failureCategory"));
            payload.put("errorMessage", safeText(
                    errorMessage == null || errorMessage.isBlank() ? "host verification failed" : errorMessage,
                    "errorMessage"));
            payload.put("reason", "Host BUILD/STATIC failed; fix the product defect named in errorMessage");
            String canonical = CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(payload));
            byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("host-verify remediation package exceeds protocol limit");
            }
            String hash = CanonicalJsonSha256.digest(canonical);
            return fromFrozen(canonical, hash);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid host-verify remediation request JSON", invalid);
        }
    }

    private static String safeText(String value, String field) {
        String normalized = requireText(value, field)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        if (normalized.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException(field + " exceeds limit");
        }
        if (SECRET.matcher(normalized).find()) {
            throw new IllegalArgumentException(field + " contains secret-like text");
        }
        if (EXTERNAL_URL.matcher(normalized).find()) {
            throw new IllegalArgumentException(field + " contains external URL");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    /**
     * Frozen HOST_VERIFY_FIX attachment plus prompt/todos derived from it.
     *
     * @param attachment hash-bound request JSON
     * @param promptSection coding prompt section
     * @param todos host-owned todos
     * @param requestHash canonical digest
     */
    public record Package(
            RequirementExecutionRequest.InitialAgentStateAttachment attachment,
            String promptSection,
            List<String> todos,
            String requestHash
    ) {
        public Package {
            if (attachment == null) {
                throw new IllegalArgumentException("attachment is required");
            }
            promptSection = requireText(promptSection, "promptSection");
            todos = todos == null ? List.of() : List.copyOf(todos);
            requestHash = requireText(requestHash, "requestHash");
        }
    }
}
