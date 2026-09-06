package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.PiQaRemediationPlanner;
import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses executor QA result JSON into a {@link QaSubject}.
 *
 * <p>Docker-metadata key names are locked to {@code exec} {@code QaExecutionMetadataKeys}
 * ({@code qaWorkspaceFingerprintBeforeJson}, {@code qaWorkspaceFingerprintAfterJson},
 * {@code qaWorkspaceIntegrity}). Engine cannot depend on {@code exec}, so the strings are
 * duplicated here and must stay identical.
 */
public final class QaSubjectExtractor {

    /** Lockstep with {@code QaExecutionMetadataKeys.WORKSPACE_FINGERPRINT_BEFORE_JSON}. */
    public static final String WORKSPACE_FINGERPRINT_BEFORE_JSON = "qaWorkspaceFingerprintBeforeJson";

    /** Lockstep with {@code QaExecutionMetadataKeys.WORKSPACE_FINGERPRINT_AFTER_JSON}. */
    public static final String WORKSPACE_FINGERPRINT_AFTER_JSON = "qaWorkspaceFingerprintAfterJson";

    /** Lockstep with {@code QaExecutionMetadataKeys.WORKSPACE_INTEGRITY}. */
    public static final String WORKSPACE_INTEGRITY = "qaWorkspaceIntegrity";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final QaEvidenceLookup EMPTY_LOOKUP = path -> Optional.empty();

    private QaSubjectExtractor() {
    }

    /**
     * Builds a QA subject from a role result JSON document, including nested aggregate envelopes.
     *
     * @param auditRunId audit run id stamped onto evidence refs
     * @param commandId durable command id
     * @param qaStageRunId QA stage run
     * @param piQaRemediationV2 whether the attempt had the v2 capability
     * @param resultJson executor result JSON
     * @param lookup optional persisted-object lookup; {@code null} means none
     * @return parsed subject
     */
    public static QaSubject fromResultJson(
            String auditRunId,
            String commandId,
            String qaStageRunId,
            boolean piQaRemediationV2,
            String resultJson,
            QaEvidenceLookup lookup
    ) {
        JsonNode root = parse(PiQaRemediationPlanner.authoritativeQaResultJson(resultJson));
        JsonNode metadata = root.path("dockerMetadata");
        QaEvidenceLookup objects = lookup == null ? EMPTY_LOOKUP : lookup;
        return new QaSubject(
                auditRunId,
                commandId,
                qaStageRunId,
                piQaRemediationV2,
                parseFingerprint(metadata, WORKSPACE_FINGERPRINT_BEFORE_JSON),
                parseFingerprint(metadata, WORKSPACE_FINGERPRINT_AFTER_JSON),
                parseCurrentAcceptances(root.path("acceptanceResults"), auditRunId, objects)
        );
    }

    private static List<QaCurrentAcceptance> parseCurrentAcceptances(
            JsonNode results,
            String auditRunId,
            QaEvidenceLookup lookup
    ) {
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<QaCurrentAcceptance> current = new ArrayList<>();
        for (JsonNode row : results) {
            if (row == null || !row.isObject()) {
                continue;
            }
            if (!"CURRENT".equalsIgnoreCase(text(row, "scope"))) {
                continue;
            }
            current.add(new QaCurrentAcceptance(
                    text(row, "criteriaId"),
                    firstNonBlank(text(row, "criteria"), text(row, "criteriaText")),
                    text(row, "status"),
                    exitCode(row),
                    evidenceRefs(row, auditRunId, lookup)
            ));
        }
        return List.copyOf(current);
    }

    private static List<EvidenceRef> evidenceRefs(JsonNode row, String auditRunId, QaEvidenceLookup lookup) {
        Set<String> paths = new LinkedHashSet<>();
        addPath(paths, text(row, "logArtifactId"));
        JsonNode extra = row.path("evidenceArtifactIds");
        if (extra.isArray()) {
            extra.forEach(item -> addPath(paths, item == null || item.isNull() ? "" : item.asText("").strip()));
        }
        List<EvidenceRef> refs = new ArrayList<>();
        for (String path : paths) {
            refs.add(toEvidenceRef(auditRunId, path, lookup));
        }
        return List.copyOf(refs);
    }

    private static EvidenceRef toEvidenceRef(String auditRunId, String path, QaEvidenceLookup lookup) {
        Optional<QaEvidenceLookup.PersistedObject> persisted = lookup.findByArtifactPath(path);
        if (persisted.isPresent() && !persisted.get().objectId().isBlank()) {
            QaEvidenceLookup.PersistedObject object = persisted.get();
            String sha = object.sha256().isBlank() ? digestPath(path) : object.sha256();
            return new EvidenceRef(
                    auditRunId,
                    EvidenceSourceKind.QA_EVIDENCE,
                    "qa-evidence://objects/" + object.objectId(),
                    sha);
        }
        return new EvidenceRef(
                auditRunId,
                EvidenceSourceKind.QA_EVIDENCE,
                path,
                digestPath(path));
    }

    private static WorkspaceFingerprintReceipt parseFingerprint(JsonNode metadata, String key) {
        if (metadata == null || !metadata.isObject()) {
            return null;
        }
        JsonNode value = metadata.get(key);
        JsonNode object = asObject(value);
        if (object == null) {
            return null;
        }
        String head = text(object, "headSha");
        String tree = text(object, "trackedTreeSha256");
        if (head.isBlank() && tree.isBlank()) {
            return null;
        }
        int count = object.path("trackedFileCount").asInt(0);
        return new WorkspaceFingerprintReceipt(head, tree, Math.max(0, count));
    }

    private static JsonNode asObject(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isObject()) {
            return value;
        }
        if (value.isTextual()) {
            JsonNode parsed = parse(value.asText());
            return parsed.isObject() ? parsed : null;
        }
        return null;
    }

    private static JsonNode parse(String raw) {
        String json = raw == null || raw.isBlank() ? "{}" : raw.strip();
        try {
            JsonNode node = JSON.readTree(json);
            return node == null || node.isNull() || !node.isObject() ? JSON.createObjectNode() : node;
        } catch (Exception ignored) {
            return JSON.createObjectNode();
        }
    }

    private static int exitCode(JsonNode row) {
        JsonNode value = row.get("exitCode");
        if (value == null || value.isNull() || value.isMissingNode() || !value.canConvertToInt()) {
            return -1;
        }
        return value.asInt();
    }

    private static void addPath(Set<String> paths, String path) {
        if (path != null && !path.isBlank()) {
            paths.add(path.strip());
        }
    }

    private static String digestPath(String path) {
        try {
            return CanonicalJsonSha256.digest(JSON.writeValueAsString(Map.of("artifactPath", path)));
        } catch (Exception failed) {
            throw new IllegalStateException("artifact path digest failed", failed);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("").strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
