package com.wish.rd.engine.requirement.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;

import java.util.List;

/**
 * Extracts candidate-patch identity from a reviewed delivery result for publication intents.
 *
 * <p>Compatible with the same two shapes used by branch publication:
 * stage-level {@code candidatePatch} manifests, and CODING_AGENT {@code stageArtifacts}
 * PATCH_DIFF entries embedded in {@code resultJson}.
 */
public final class RequirementPublicationIntentFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RequirementPublicationIntentFactory() {
    }

    /**
     * Returns the candidate patch sha256 when present, otherwise blank.
     *
     * @param deliveryResultJson reviewed delivery JSON
     * @return normalized sha256 (may include {@code sha256:} prefix) or blank
     */
    public static String candidatePatchSha256(String deliveryResultJson) {
        if (deliveryResultJson == null || deliveryResultJson.isBlank()) {
            return "";
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(deliveryResultJson);
        } catch (JsonProcessingException exception) {
            return "";
        }
        if (root == null || !root.isObject()) {
            return "";
        }
        for (String stagesField : List.of("multiAgentStages", "stages")) {
            JsonNode stages = root.path(stagesField);
            if (!stages.isArray()) {
                continue;
            }
            for (JsonNode stage : stages) {
                if (stage == null || !stage.isObject()) {
                    continue;
                }
                String fromManifest = shaFromManifest(stage.path("candidatePatch"));
                if (!fromManifest.isBlank()) {
                    return fromManifest;
                }
                String fromArtifacts = shaFromStageArtifacts(
                        stage.path("role").asText(""),
                        embeddedRoleResult(stage.path("resultJson"))
                );
                if (!fromArtifacts.isBlank()) {
                    return fromArtifacts;
                }
            }
        }
        return shaFromStageArtifacts(AgentRole.CODING_AGENT.name(), root);
    }

    private static String shaFromManifest(JsonNode candidatePatch) {
        if (candidatePatch == null || !candidatePatch.isObject()) {
            return "";
        }
        String sha256 = safe(candidatePatch.path("sha256").asText(""));
        long bytes = candidatePatch.path("bytes").asLong(-1L);
        String artifactUri = safe(candidatePatch.path("artifactUri").asText(""));
        if (!artifactUri.startsWith("s3://") || sha256.isBlank() || bytes <= 0L) {
            return "";
        }
        return sha256;
    }

    private static String shaFromStageArtifacts(String role, JsonNode roleResult) {
        if (!AgentRole.CODING_AGENT.name().equalsIgnoreCase(safe(role))
                || roleResult == null
                || !roleResult.path("stageArtifacts").isArray()) {
            return "";
        }
        for (JsonNode artifact : roleResult.path("stageArtifacts")) {
            if (artifact == null || !artifact.isObject()
                    || !"PATCH_DIFF".equalsIgnoreCase(safe(artifact.path("type").asText("")))
                    || !"patch.diff".equals(safe(artifact.path("name").asText("")))) {
                continue;
            }
            JsonNode metadata = artifact.path("metadataJson");
            if (!"true".equalsIgnoreCase(safe(metadata.path("candidatePatch").asText("")))) {
                continue;
            }
            String artifactUri = safe(artifact.path("uri").asText(""));
            String sha256 = safe(metadata.path("sha256").asText(""));
            long bytes = metadata.path("bytes").asLong(-1L);
            if (bytes <= 0L && metadata.path("bytes").isTextual()) {
                try {
                    bytes = Long.parseLong(safe(metadata.path("bytes").asText("")));
                } catch (NumberFormatException ignored) {
                    bytes = -1L;
                }
            }
            if (!artifactUri.startsWith("s3://") || sha256.isBlank() || bytes <= 0L) {
                continue;
            }
            return sha256;
        }
        return "";
    }

    private static JsonNode embeddedRoleResult(JsonNode rawResult) {
        if (rawResult == null || rawResult.isMissingNode() || rawResult.isNull()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        if (rawResult.isObject()) {
            return rawResult;
        }
        if (!rawResult.isTextual()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(safe(rawResult.asText()));
            return parsed != null && parsed.isObject() ? parsed : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
