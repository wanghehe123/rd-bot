package com.wish.rd.exec.repair.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonical docker-metadata keys for host-owned QA execution decisions.
 *
 * <p>The host {@code EngineRequirementExecutorAdapter} re-reads these keys from
 * {@code RepairExecutionResult.dockerMetadataJson()} after the container exits.
 * Repository / GitHub metadata must never carry them: a security-relevant
 * fail-closed decision has one well-defined channel.
 */
public final class QaExecutionMetadataKeys {

    /** Docker-metadata key for the host-computed docs-only decision source. */
    public static final String DECISION_SOURCE = "qaDecisionSource";

    /**
     * Docker-metadata key for the JSON array of candidate changed files used by
     * {@code QaEvidenceBundleValidator} docs-only checks.
     */
    public static final String CANDIDATE_CHANGED_FILES_JSON = "qaCandidateChangedFilesJson";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QaExecutionMetadataKeys() {
    }

    /**
     * Reads the host-owned candidate changed-file set from docker metadata.
     *
     * @param dockerMetadata executor docker metadata; {@code null} means undeterminable
     * @return changed paths, or {@code null} when missing/blank/malformed so callers fail closed
     */
    public static List<String> candidateChangedFilesFrom(Map<String, String> dockerMetadata) {
        if (dockerMetadata == null) {
            return null;
        }
        String raw = dockerMetadata.get(CANDIDATE_CHANGED_FILES_JSON);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            if (root == null || !root.isArray()) {
                return null;
            }
            List<String> paths = new ArrayList<>();
            root.forEach(item -> {
                String path = item.asText("").strip();
                if (!path.isBlank()) {
                    paths.add(path);
                }
            });
            return List.copyOf(paths);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
