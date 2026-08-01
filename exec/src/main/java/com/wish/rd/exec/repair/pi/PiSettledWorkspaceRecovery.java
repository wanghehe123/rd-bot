package com.wish.rd.exec.repair.pi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.model.StructuredResultValidation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reconciles a persisted Pi workspace output directory after orchestration interruption.
 */
public final class PiSettledWorkspaceRecovery {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final StructuredResultValidator RESULT_VALIDATOR = new StructuredResultValidator();
    private static final AgentRoleResultValidator ROLE_RESULT_VALIDATOR = new AgentRoleResultValidator();

    private final Path workspaceRoot;

    public PiSettledWorkspaceRecovery(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Attempts to recover a settled workspace output for the given stage attempt.
     *
     * <p>Workspace directories follow {@code EngineRequirementExecutorAdapter#executionTaskId}:
     * coding uses the workflow task id; other roles use {@code taskId-role.lowerCase()}.
     * Bridge events still carry the workflow {@code taskId}.
     */
    public Optional<RecoveredWorkspaceSnapshot> tryRecover(String taskId, String stageRunId, String role)
            throws IOException {
        for (Path outputDirectory : candidateOutputDirectories(taskId, role)) {
            Optional<RecoveredWorkspaceSnapshot> recovered = tryRecoverFromOutput(
                    outputDirectory, taskId, stageRunId, role);
            if (recovered.isPresent()) {
                return recovered;
            }
        }
        return Optional.empty();
    }

    private Optional<RecoveredWorkspaceSnapshot> tryRecoverFromOutput(
            Path outputDirectory,
            String taskId,
            String stageRunId,
            String role
    ) throws IOException {
        if (!outputDirectory.startsWith(workspaceRoot) || !Files.isDirectory(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path eventsFile = outputDirectory.resolve("agent-events.jsonl");
        PiWorkspaceLifecycleInspector.LifecycleSnapshot lifecycle = PiWorkspaceLifecycleInspector.inspect(
                eventsFile,
                taskId,
                stageRunId,
                role
        );
        if (!lifecycle.complete()) {
            return Optional.empty();
        }
        Path resultPath = outputDirectory.resolve("result.json");
        if (!Files.isRegularFile(resultPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String rawResultJson = Files.readString(resultPath, StandardCharsets.UTF_8);
        List<RepairArtifact> artifacts = PiWorkspaceArtifactCollector.collect(outputDirectory);
        Map<String, String> runtimeMeta = readRuntimeMeta(outputDirectory.resolve("runtime-meta.json"));
        ValidationOutcome validation = validate(role, rawResultJson, artifacts);
        String resultJson = toResultJson(rawResultJson, validation, artifacts, runtimeMeta);
        return Optional.of(new RecoveredWorkspaceSnapshot(
                validation.success(),
                resultJson,
                validation.errorCategory(),
                validation.errorMessage(),
                runtimeMeta.getOrDefault("provider", ""),
                "[]"
        ));
    }

    private List<Path> candidateOutputDirectories(String taskId, String role) {
        String normalizedTaskId = taskId == null ? "" : taskId.strip();
        String normalizedRole = role == null ? "" : role.strip();
        List<Path> candidates = new ArrayList<>();
        if (!normalizedTaskId.isBlank() && !normalizedRole.isBlank()
                && !"CODING_AGENT".equalsIgnoreCase(normalizedRole)) {
            candidates.add(workspaceRoot
                    .resolve(normalizedTaskId + "-" + normalizedRole.toLowerCase(Locale.ROOT))
                    .resolve("output")
                    .normalize());
        }
        if (!normalizedTaskId.isBlank()) {
            candidates.add(workspaceRoot.resolve(normalizedTaskId).resolve("output").normalize());
        }
        return List.copyOf(candidates);
    }

    private static ValidationOutcome validate(String role, String rawResultJson, List<RepairArtifact> artifacts) {
        if (usesAgentRoleProtocol(role)) {
            AgentRoleResultValidation validation = ROLE_RESULT_VALIDATOR.validate(role, rawResultJson);
            if (!validation.valid()) {
                return ValidationOutcome.rejected(
                        "PI_RESULT_PROTOCOL",
                        String.join("; ", validation.errors())
                );
            }
            List<String> missing = missingSuccessArtifacts(role, artifacts);
            if (!missing.isEmpty()) {
                return ValidationOutcome.rejected(
                        "PI_RESULT_PROTOCOL",
                        String.join("; ", missing)
                );
            }
            String status = text(parseJson(rawResultJson).path("status"));
            return isSuccessStatus(status)
                    ? ValidationOutcome.accepted()
                    : ValidationOutcome.rejected("AGENT_RESULT_REJECTED", failureSummary(rawResultJson, role));
        }
        StructuredResultValidation validation = RESULT_VALIDATOR.validate(rawResultJson);
        if (!validation.valid()) {
            return ValidationOutcome.rejected(
                    "PI_RESULT_PROTOCOL",
                    String.join("; ", validation.errors())
            );
        }
        if (!"SUCCESS".equalsIgnoreCase(validation.result().status())) {
            return ValidationOutcome.rejected(
                    "AGENT_RESULT_REJECTED",
                    validation.result().summary().isBlank()
                            ? "agent result status is not SUCCESS"
                            : validation.result().summary()
            );
        }
        List<String> missing = missingSuccessArtifacts(role, artifacts);
        if (!missing.isEmpty()) {
            return ValidationOutcome.rejected("PI_RESULT_PROTOCOL", String.join("; ", missing));
        }
        return ValidationOutcome.accepted();
    }

    private static String toResultJson(
            String rawResultJson,
            ValidationOutcome validation,
            List<RepairArtifact> artifacts,
            Map<String, String> runtimeMeta
    ) throws JsonProcessingException {
        ObjectNode root = parseJson(rawResultJson).deepCopy();
        if (validation.success()) {
            root.put("status", text(root.path("status")).isBlank() ? "SUCCESS" : text(root.path("status")));
        } else {
            root.put("status", "FAILED");
            root.put("errorMessage", validation.errorMessage());
        }
        root.put("summary", firstNonBlank(text(root.path("summary")), validation.errorMessage(), "Recovered Pi result"));
        root.put("pullRequestUrl", "");
        root.set("dockerMetadata", OBJECT_MAPPER.valueToTree(runtimeMeta));
        root.put("codePlatformMetadata", OBJECT_MAPPER.createObjectNode());
        root.set("stageArtifacts", OBJECT_MAPPER.valueToTree(stageArtifacts(artifacts, runtimeMeta)));
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private static List<Map<String, Object>> stageArtifacts(
            List<RepairArtifact> artifacts,
            Map<String, String> runtimeMeta
    ) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (RepairArtifact artifact : artifacts) {
            if (isRestrictedPrivateArtifact(artifact)) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", artifact.type().name());
            value.put("name", artifact.name());
            value.put("uri", artifact.uri());
            value.put("summary", artifact.summary());
            value.put("contentPreview", artifact.metadataJson().getOrDefault("contentPreview", ""));
            Map<String, String> metadata = new LinkedHashMap<>(artifact.metadataJson());
            metadata.put("artifactName", artifact.name());
            value.put("metadataJson", Map.copyOf(metadata));
            values.add(Map.copyOf(value));
        }
        if (!runtimeMeta.isEmpty()) {
            Map<String, Object> runtimeArtifact = new LinkedHashMap<>();
            runtimeArtifact.put("type", RepairArtifactType.AGENT_RUNTIME_META.name());
            runtimeArtifact.put("name", "runtime-meta.json");
            runtimeArtifact.put("uri", "");
            runtimeArtifact.put("summary", "Pi runtime metadata");
            runtimeArtifact.put("contentPreview", runtimeMeta);
            runtimeArtifact.put("metadataJson", runtimeMeta);
            values.add(Map.copyOf(runtimeArtifact));
        }
        return List.copyOf(values);
    }

    private static boolean isRestrictedPrivateArtifact(RepairArtifact artifact) {
        return artifact != null
                && ("true".equalsIgnoreCase(artifact.metadataJson().get("restricted"))
                || artifact.type() == RepairArtifactType.PI_RAW_EVENTS
                || artifact.type() == RepairArtifactType.PI_SESSION);
    }

    private static List<String> missingSuccessArtifacts(String role, List<RepairArtifact> artifacts) {
        List<String> required = switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "CODING_AGENT" -> List.of("patch.diff", "test.log", "agent-events.jsonl");
            case "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT" -> List.of("handoff/next.md", "agent-events.jsonl");
            case "QA_AGENT" -> List.of("agent-events.jsonl");
            default -> List.of("agent-events.jsonl");
        };
        List<String> present = artifacts.stream().map(RepairArtifact::name).toList();
        return required.stream().filter(name -> !present.contains(name)).toList();
    }

    private static boolean usesAgentRoleProtocol(String role) {
        return "REQUIREMENT_REVIEWER".equals(role)
                || "SOLUTION_ARCHITECT".equals(role)
                || "QA_AGENT".equals(role);
    }

    private static boolean isSuccessStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "PASSED".equalsIgnoreCase(status);
    }

    private static String failureSummary(String rawResultJson, String role) {
        JsonNode root = parseJson(rawResultJson);
        String summary = text(root.path("summary"));
        if (!summary.isBlank()) {
            return summary;
        }
        return "QA_AGENT".equals(role) ? "QA_AGENT failed acceptance" : "agent result status is not SUCCESS";
    }

    private static Map<String, String> readRuntimeMeta(Path runtimeMetaPath) throws IOException {
        if (!Files.isRegularFile(runtimeMetaPath, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        JsonNode root = OBJECT_MAPPER.readTree(Files.readString(runtimeMetaPath, StandardCharsets.UTF_8));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("executionProfileSnapshotId", text(root.path("snapshotId")));
        metadata.put("provider", text(root.path("provider")));
        metadata.put("model", text(root.path("model")));
        metadata.put("runtime", text(root.path("runtime")));
        metadata.put("settled", String.valueOf(root.path("settled").asBoolean(false)));
        metadata.put("resultAccepted", String.valueOf(root.path("resultAccepted").asBoolean(false)));
        return Map.copyOf(metadata);
    }

    private static ObjectNode parseJson(String rawJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawJson == null ? "{}" : rawJson);
            return root instanceof ObjectNode objectNode ? objectNode : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private record ValidationOutcome(boolean success, String errorCategory, String errorMessage) {

        private static ValidationOutcome accepted() {
            return new ValidationOutcome(true, "", "");
        }

        private static ValidationOutcome rejected(String category, String message) {
            return new ValidationOutcome(false, category, message == null ? "" : message.strip());
        }
    }

    /**
     * Recovered workspace snapshot for engine reconciliation.
     */
    public record RecoveredWorkspaceSnapshot(
            boolean success,
            String resultJson,
            String errorCategory,
            String errorMessage,
            String providerName,
            String providerAttemptsJson
    ) {
    }
}
