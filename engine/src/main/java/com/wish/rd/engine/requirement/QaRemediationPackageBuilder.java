package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds the only Host-controlled QA-to-Coding remediation attachment. */
public final class QaRemediationPackageBuilder {
    public static final String ATTACHMENT_PATH = "attachments/qa-remediation/request.json";
    public static final String CONTAINER_PATH = "/work/input/attachments/qa-remediation/request.json";
    private static final int MAX_JSON_BYTES = 65_536;
    private static final int MAX_TEXT_CHARS = 4_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(bearer\\s+\\S+|api[_-]?key\\s*[:=]|secret\\s*[:=]|password\\s*[:=]|sk-[a-z0-9_-]{8,})");
    private static final Pattern EXTERNAL_URL = Pattern.compile("(?i)https?://|s3://|file://");

    /** Rehydrates an already sanitized immutable ledger request without consulting live QA state. */
    public Package fromFrozen(String requestJson, String requestHash) {
        String hash = requireText(requestHash, "requestHash").toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("frozen QA remediation request identity is invalid");
        }
        String canonical;
        try {
            canonical = CanonicalJsonSha256.requireCanonicalMatchingHash(
                    requireText(requestJson, "requestJson"), hash);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("frozen QA remediation request identity is invalid", invalid);
        }
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("frozen QA remediation request identity is invalid");
        }
        try {
            JsonNode root = MAPPER.readTree(canonical);
            if (!"rd-qa-remediation-request/v2".equals(root.path("protocol").asText())) {
                throw new IllegalArgumentException("unsupported frozen QA remediation protocol");
            }
            String source = requireText(root.path("sourceQaStageRunId").asText(), "sourceQaStageRunId");
            int remediationNo = root.path("remediationNo").asInt(0);
            if (remediationNo < 1 || remediationNo > 2) {
                throw new IllegalArgumentException("remediationNo must be between 1 and 2");
            }
            String reason = safeText(root.path("reason").asText(), "reason");
            JsonNode findings = root.path("bugFindings");
            if (!findings.isArray() || findings.isEmpty() || findings.size() > 32) {
                throw new IllegalArgumentException("frozen bugFindings must be a non-empty bounded array");
            }
            List<String> promptLines = new ArrayList<>();
            List<String> todos = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode finding : findings) {
                String id = safeText(finding.path("id").asText(), "finding id");
                if (!ids.add(id)) throw new IllegalArgumentException("duplicate frozen bug finding: " + id);
                String severity = safeText(finding.path("severity").asText(), "severity");
                String criteria = safeText(finding.path("acceptanceCriteriaId").asText(), "acceptanceCriteriaId");
                textList(finding.path("reproductionSteps"), "reproductionSteps");
                String expected = safeText(finding.path("expected").asText(), "expected");
                String actual = safeText(finding.path("actual").asText(), "actual");
                Set<String> evidence = textSet(finding.path("evidenceArtifactIds"), "evidenceArtifactIds", true);
                evidence.forEach(value -> requireSafeEvidencePath(value, "evidenceArtifactId"));
                textSet(finding.path("suspectedFiles"), "suspectedFiles", true)
                        .forEach(value -> requireSafeRelativePath(value, "suspectedFile"));
                promptLines.add("- " + id + " [" + severity + "] / " + criteria
                        + ": expected=" + expected + "; actual=" + actual
                        + "; evidence=" + String.join(",", evidence));
                todos.add("修复并复验 " + id + "（验收 " + criteria + "），引用已验证证据："
                        + String.join(",", evidence));
            }
            RequirementExecutionRequest.InitialAgentStateAttachment attachment =
                    new RequirementExecutionRequest.InitialAgentStateAttachment(
                            ATTACHMENT_PATH, canonical, hash, bytes.length);
            String prompt = "QA remediation round " + remediationNo + " from stage " + source + ".\n"
                    + "Authoritative request: " + CONTAINER_PATH + " (" + hash + ")\n"
                    + "Reason: " + reason + "\nRequired fixes:\n" + String.join("\n", promptLines);
            return new Package(attachment, prompt, todos, hash);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid frozen QA remediation request JSON", invalid);
        }
    }

    public Package build(String sourceQaStageRunId, int remediationNo, String qaResultJson) {
        String source = requireText(sourceQaStageRunId, "sourceQaStageRunId");
        if (remediationNo < 1 || remediationNo > 2) {
            throw new IllegalArgumentException("remediationNo must be between 1 and 2");
        }
        if (qaResultJson == null || qaResultJson.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw new IllegalArgumentException("QA remediation result exceeds Host input limit");
        }
        try {
            JsonNode root = MAPPER.readTree(qaResultJson);
            JsonNode request = root == null ? null : root.path("remediationRequest");
            if (root == null || !root.isObject() || !"FAILED".equals(root.path("status").asText())
                    || !request.path("requested").asBoolean(false)
                    || !"CODING_AGENT".equals(request.path("targetRole").asText())) {
                throw new IllegalArgumentException("QA result does not contain a requested Coding remediation");
            }
            String reason = safeText(request.path("reason").asText(), "reason");
            Set<String> selected = textSet(request.path("bugFindingIds"), "bugFindingIds", false);
            if (selected.isEmpty() || selected.size() > 32) {
                throw new IllegalArgumentException("bugFindingIds must contain between 1 and 32 values");
            }
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("protocol", "rd-qa-remediation-request/v2");
            payload.put("sourceQaStageRunId", source);
            payload.put("remediationNo", remediationNo);
            payload.put("reason", reason);
            ArrayNode findings = payload.putArray("bugFindings");
            List<String> promptLines = new ArrayList<>();
            List<String> todos = new ArrayList<>();
            Set<String> found = new LinkedHashSet<>();
            for (JsonNode finding : root.path("bugFindings")) {
                String id = safeText(finding.path("id").asText(), "finding id");
                if (!selected.contains(id)) continue;
                if (!found.add(id)) throw new IllegalArgumentException("duplicate selected bug finding: " + id);
                String severity = safeText(finding.path("severity").asText(), "severity");
                String criteria = safeText(finding.path("acceptanceCriteriaId").asText(), "acceptanceCriteriaId");
                List<String> steps = textList(finding.path("reproductionSteps"), "reproductionSteps");
                String expected = safeText(finding.path("expected").asText(), "expected");
                String actual = safeText(finding.path("actual").asText(), "actual");
                Set<String> evidence = textSet(finding.path("evidenceArtifactIds"), "evidenceArtifactIds", true);
                Set<String> suspectedFiles = textSet(finding.path("suspectedFiles"), "suspectedFiles", true);
                evidence.forEach(value -> requireSafeEvidencePath(value, "evidenceArtifactId"));
                suspectedFiles.forEach(value -> requireSafeRelativePath(value, "suspectedFile"));

                ObjectNode item = findings.addObject();
                item.put("id", id);
                item.put("severity", severity);
                item.put("acceptanceCriteriaId", criteria);
                item.putPOJO("reproductionSteps", steps);
                item.put("expected", expected);
                item.put("actual", actual);
                item.putPOJO("evidenceArtifactIds", List.copyOf(evidence));
                item.putPOJO("suspectedFiles", List.copyOf(suspectedFiles));
                promptLines.add("- " + id + " [" + severity + "] / " + criteria
                        + ": expected=" + expected + "; actual=" + actual
                        + "; evidence=" + String.join(",", evidence));
                todos.add("修复并复验 " + id + "（验收 " + criteria + "），引用已验证证据："
                        + String.join(",", evidence));
            }
            if (!found.equals(selected)) {
                throw new IllegalArgumentException("remediation request references unknown bug finding IDs");
            }
            String canonical = CanonicalJsonSha256.canonicalize(MAPPER.writeValueAsString(payload));
            byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("QA remediation package exceeds protocol limit");
            }
            String hash = CanonicalJsonSha256.digest(canonical);
            RequirementExecutionRequest.InitialAgentStateAttachment attachment =
                    new RequirementExecutionRequest.InitialAgentStateAttachment(
                            ATTACHMENT_PATH, canonical, hash, bytes.length);
            String prompt = "QA remediation round " + remediationNo + " from stage " + source + ".\n"
                    + "Authoritative request: " + CONTAINER_PATH + " (" + hash + ")\n"
                    + "Reason: " + reason + "\nRequired fixes:\n" + String.join("\n", promptLines);
            return new Package(attachment, prompt, todos, hash);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid QA remediation result JSON", invalid);
        }
    }

    private static List<String> textList(JsonNode node, String field) {
        if (!node.isArray() || node.isEmpty() || node.size() > 32) {
            throw new IllegalArgumentException(field + " must be a non-empty bounded array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(safeText(item.asText(), field));
        return List.copyOf(values);
    }

    private static Set<String> textSet(JsonNode node, String field, boolean allowEmpty) {
        if (!node.isArray() || node.size() > 32 || (!allowEmpty && node.isEmpty())) {
            throw new IllegalArgumentException(field + " must be a bounded array");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) values.add(safeText(item.asText(), field));
        return java.util.Collections.unmodifiableSet(values);
    }

    private static String safeText(String value, String field) {
        String normalized = requireText(value, field)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        if (normalized.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException(field + " exceeds limit");
        if (SECRET.matcher(normalized).find()) throw new IllegalArgumentException(field + " contains secret-like text");
        if (EXTERNAL_URL.matcher(normalized).find()) throw new IllegalArgumentException(field + " contains external URL");
        return normalized;
    }

    private static void requireSafeEvidencePath(String value, String field) {
        requireSafeRelativePath(value, field);
        if (!value.startsWith("qa-evidence/")) {
            throw new IllegalArgumentException(field + " must remain under qa-evidence/");
        }
    }

    private static void requireSafeRelativePath(String value, String field) {
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("..")
                || value.contains(":") || !value.matches("[A-Za-z0-9._/@+-]+")) {
            throw new IllegalArgumentException(field + " is unsafe");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public record Package(
            RequirementExecutionRequest.InitialAgentStateAttachment attachment,
            String promptSection,
            List<String> todos,
            String requestHash
    ) {
        public Package {
            if (attachment == null) throw new IllegalArgumentException("attachment is required");
            promptSection = requireText(promptSection, "promptSection");
            todos = todos == null ? List.of() : List.copyOf(todos);
            requestHash = requireText(requestHash, "requestHash");
        }
    }
}
