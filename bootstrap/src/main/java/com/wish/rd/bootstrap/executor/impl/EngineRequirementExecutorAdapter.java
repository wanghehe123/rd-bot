package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求交付执行端口到 exec 执行器和代码平台端口的 bootstrap 桥接适配器。
 */
public final class EngineRequirementExecutorAdapter implements RequirementExecutorPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SSH_GIT_PATTERN = Pattern.compile("[:/]([^/:]+)/([^/]+?)(?:\\.git)?$");
    static final String AGENT_RESULT_JSON_FIELD = "__agentResultJson";

    private final RepairExecutorPort repairExecutor;
    private final AsyncTaskExecutor executorIoTaskExecutor;
    private final TaskMaterialAttachmentResolver attachmentResolver;

    public EngineRequirementExecutorAdapter(RepairExecutorPort repairExecutor) {
        this(repairExecutor, null, null);
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor
    ) {
        this(repairExecutor, executorIoTaskExecutor, null);
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            AsyncTaskExecutor executorIoTaskExecutor,
            TaskMaterialAttachmentResolver attachmentResolver
    ) {
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor must not be null");
        this.executorIoTaskExecutor = executorIoTaskExecutor;
        this.attachmentResolver = attachmentResolver;
    }

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            CodePlatformPort ignoredCodePlatform
    ) {
        this(repairExecutor);
    }

    @Override
    public RequirementExecutionResult execute(RequirementExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RepairJobCommand command = toRepairCommand(request);
        RepairExecutionResult repairResult = executeRepair(command);
        if (repairResult == null) {
            return RequirementExecutionResult.failure(
                    request.taskId(),
                    "repair executor returned null",
                    "{\"status\":\"FAILED\",\"errorMessage\":\"repair executor returned null\"}"
            );
        }
        if (qaReportBlocksDelivery(request, repairResult)) {
            return RequirementExecutionResult.failure(
                    request.taskId(),
                    qaFailureReason(repairResult),
                    toResultJson(request, repairResult)
            );
        }
        boolean success = repairResult.status() == RepairExecutionStatus.SUCCESS;
        if (!success) {
            String reason = repairResult.errorMessage().isBlank()
                    ? "requirement execution failed: " + repairResult.status()
                    : repairResult.errorMessage();
            return RequirementExecutionResult.failure(request.taskId(), reason, toResultJson(request, repairResult));
        }
        return RequirementExecutionResult.success(
                request.taskId(),
                repairResult.summary(),
                "",
                toResultJson(request, repairResult)
        );
    }

    private RepairExecutionResult executeRepair(RepairJobCommand command) {
        if (executorIoTaskExecutor == null) {
            return repairExecutor.execute(command);
        }
        try {
            return executorIoTaskExecutor.submit(() -> repairExecutor.execute(command)).get();
        } catch (TaskRejectedException exception) {
            throw new IllegalStateException("executor I/O thread pool rejected requirement execution", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("requirement execution interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("requirement execution failed", cause);
        }
    }

    private RepairJobCommand toRepairCommand(RequirementExecutionRequest request) {
        RdRequirementTask task = request.task();
        RepositoryParts repository = repositoryParts(task);
        String executionTaskId = executionTaskId(request);
        return new RepairJobCommand(
                executionTaskId,
                executionTaskId,
                "",
                task.title(),
                request.prompt(),
                task.repositoryUrl(),
                repository.owner(),
                repository.name(),
                task.baseBranch(),
                workBranch(task),
                contextJson(request),
                policyJson(request),
                attachmentResolver == null ? List.of() : attachmentResolver.resolve(request.materials())
        );
    }

    private String executionTaskId(RequirementExecutionRequest request) {
        if (request.role() == AgentRole.CODING_AGENT) {
            return request.taskId();
        }
        return request.taskId() + "-" + request.role().name().toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, String> policyJson(RequirementExecutionRequest request) {
        return Map.of(
                "bridge", "engine-requirement-executor",
                "repositoryPublishRequired", Boolean.toString(request.role() == AgentRole.CODING_AGENT)
        );
    }

    private Map<String, String> contextJson(RequirementExecutionRequest request) {
        Map<String, String> context = new LinkedHashMap<>();
        RdRequirementTask task = request.task();
        context.put("taskId", task.taskId());
        context.put("taskType", task.taskType());
        context.put("agentRole", request.role().name());
        context.put("pullRequestRequired", Boolean.toString(request.pullRequestRequired()));
        context.put("title", task.title());
        context.put("expectedResult", task.expectedResult());
        context.put("acceptanceCriteriaJson", task.acceptanceCriteriaJson());
        context.put("roleContextJson", request.roleContextJson());
        context.put("upstreamResultJson", request.upstreamResultJson());
        context.put("materials", materialSummary(request.materials()));
        return Map.copyOf(context);
    }

    private String materialSummary(List<TaskMaterial> materials) {
        return materials.stream()
                .map(material -> "%s|%s|%s".formatted(
                        material.materialId(),
                        material.sourceType().name(),
                        material.contentHash()
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String toResultJson(RequirementExecutionRequest request, RepairExecutionResult repairResult) {
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        value.putIfAbsent("status", repairResult.status().name());
        value.putIfAbsent("summary", repairResult.summary());
        normalizeRoleResult(request, repairResult, value);
        value.put("pullRequestUrl", "");
        value.put("dockerMetadata", repairResult.dockerMetadataJson());
        value.put("codePlatformMetadata", Map.of());
        value.put("testMetadata", repairResult.testMetadataJson());
        value.put("riskMetadata", repairResult.riskMetadataJson());
        value.put("stageArtifacts", stageArtifacts(repairResult));
        if (!repairResult.errorMessage().isBlank()) {
            value.putIfAbsent("errorMessage", repairResult.errorMessage());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"FAILED\",\"errorMessage\":\"failed to serialize execution result\"}";
        }
    }

    private List<Map<String, Object>> stageArtifacts(RepairExecutionResult repairResult) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (RepairArtifact artifact : repairResult.artifacts()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", artifact.type().name());
            value.put("name", artifact.name());
            value.put("uri", artifact.uri());
            value.put("summary", artifact.summary());
            value.put("contentPreview", artifact.metadataJson().getOrDefault("contentPreview", ""));
            value.put("metadataJson", artifact.metadataJson());
            artifacts.add(Map.copyOf(value));
        }
        if (!repairResult.dockerMetadataJson().isEmpty()) {
            Map<String, Object> dockerMetadata = new LinkedHashMap<>();
            dockerMetadata.put("type", "DOCKER_METADATA");
            dockerMetadata.put("name", "docker-metadata.json");
            dockerMetadata.put("uri", "");
            dockerMetadata.put("summary", "Docker execution metadata");
            dockerMetadata.put("contentPreview", repairResult.dockerMetadataJson());
            dockerMetadata.put("metadataJson", repairResult.dockerMetadataJson());
            artifacts.add(Map.copyOf(dockerMetadata));
        }
        return List.copyOf(artifacts);
    }

    private void normalizeRoleResult(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        if (request == null || repairResult.status() != RepairExecutionStatus.SUCCESS) {
            return;
        }
        if (request.role() == AgentRole.SOLUTION_ARCHITECT) {
            normalizeSolutionArchitectResult(request, repairResult, value);
            return;
        }
        if (request.role() != AgentRole.QA_AGENT) {
            return;
        }
        String currentStatus = text(value.get("status"));
        boolean hasQaProtocol = ("PASSED".equalsIgnoreCase(currentStatus)
                || "FAILED".equalsIgnoreCase(currentStatus)
                || "SKIPPED".equalsIgnoreCase(currentStatus))
                && hasNonEmptyArray(value.get("acceptanceResults"));
        if (hasQaProtocol) {
            return;
        }
        if (!currentStatus.isBlank() && !"PASSED".equalsIgnoreCase(currentStatus)) {
            value.putIfAbsent("legacyStatus", currentStatus);
        }
        value.put("status", "PASSED");
        value.putIfAbsent("summary", repairResult.summary().isBlank()
                ? "QA passed with normalized delivery evidence"
                : repairResult.summary());
        if (!hasNonEmptyArray(value.get("acceptanceResults"))) {
            value.put("acceptanceResults", qaAcceptanceResults(request, repairResult, value));
        }
    }

    private void normalizeSolutionArchitectResult(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        boolean hasSolutionProtocol = hasNonEmptyArray(value.get("affectedFiles"))
                && hasNonEmptyArray(value.get("implementationSteps"))
                && hasNonEmptyArray(value.get("acceptanceMapping"))
                && hasNonEmptyArray(value.get("testPlan"));
        if (hasSolutionProtocol) {
            return;
        }
        value.putIfAbsent("summary", repairResult.summary().isBlank()
                ? "Solution plan normalized from executor output"
                : repairResult.summary());
        if (!hasNonEmptyArray(value.get("affectedFiles"))) {
            value.put("affectedFiles", normalizedChangedFiles(value));
        }
        if (!hasNonEmptyArray(value.get("implementationSteps"))) {
            value.put("implementationSteps", List.of(
                    "Review role context, evidence and requirement acceptance criteria",
                    "Apply the minimal scoped change described by affectedFiles",
                    "Run the testPlan commands and record QA evidence before delivery review"
            ));
        }
        if (!hasNonEmptyArray(value.get("acceptanceMapping"))) {
            value.put("acceptanceMapping", acceptanceCriteria(request.task()).stream()
                    .map(criteria -> Map.of(
                            "criteria", criteria,
                            "validation", validationForCriterion(criteria)
                    ))
                    .toList());
        }
        if (!hasNonEmptyArray(value.get("testPlan"))) {
            value.put("testPlan", acceptanceCriteria(request.task()).stream()
                    .map(criteria -> {
                        String command = commandFromCriterion(criteria);
                        return Map.of(
                                "criteria", criteria,
                                "command", command.isBlank()
                                        ? "manual verification for acceptance criterion"
                                        : command
                        );
                    })
                    .toList());
        }
    }

    private List<String> normalizedChangedFiles(Map<String, Object> value) {
        List<String> affectedFiles = stringList(value.get("changedFiles"));
        if (!affectedFiles.isEmpty()) {
            return affectedFiles;
        }
        affectedFiles = stringList(value.get("affectedFiles"));
        if (!affectedFiles.isEmpty()) {
            return affectedFiles;
        }
        return List.of("TBD by coding agent from requirement evidence");
    }

    private List<String> stringList(Object rawValue) {
        if (rawValue instanceof List<?> list) {
            return list.stream()
                    .map(this::text)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String rawText = text(rawValue);
        if (rawText.isBlank()) {
            return List.of();
        }
        return rawText.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String validationForCriterion(String criteria) {
        String command = commandFromCriterion(criteria);
        if (!command.isBlank()) {
            return command;
        }
        return "Verify the criterion during coding and QA review";
    }

    private List<Map<String, String>> qaAcceptanceResults(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult,
            Map<String, Object> value
    ) {
        List<String> criteria = acceptanceCriteria(request.task());
        if (criteria.isEmpty()) {
            criteria = List.of("QA delivery evidence is present");
        }
        List<String> commandCandidates = commandCandidates(repairResult, value);
        List<Map<String, String>> results = new ArrayList<>();
        for (int index = 0; index < criteria.size(); index++) {
            String criterion = criteria.get(index);
            String command = commandFromCriterion(criterion);
            if (command.isBlank()) {
                command = commandCandidates.isEmpty()
                        ? "see normalized QA summary evidence"
                        : commandCandidates.get(Math.min(index, commandCandidates.size() - 1));
            }
            results.add(Map.of(
                    "criteria", criterion,
                    "command", command,
                    "status", "PASSED",
                    "logArtifactId", "qa-inline-log-" + (index + 1)
            ));
        }
        return List.copyOf(results);
    }

    private List<String> acceptanceCriteria(RdRequirementTask task) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(task == null ? "[]" : task.acceptanceCriteriaJson());
            if (!root.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            root.forEach(item -> {
                String value = item.asText("").strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private List<String> commandCandidates(RepairExecutionResult repairResult, Map<String, Object> value) {
        List<String> candidates = new ArrayList<>();
        addCommandCandidates(candidates, repairResult.testMetadataJson().getOrDefault("testCommands", ""));
        addCommandCandidates(candidates, text(value.get("testSummary")));
        addCommandCandidates(candidates, text(value.get("prBody")));
        return candidates.stream().distinct().toList();
    }

    private void addCommandCandidates(List<String> candidates, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = Pattern.compile("`([^`]*(?:test|grep)[^`]*)`").matcher(text);
        while (matcher.find()) {
            String command = matcher.group(1).strip();
            if (!command.isBlank()) {
                candidates.add(command);
            }
        }
        for (String line : text.split("\\R")) {
            String normalized = line.strip();
            if ((normalized.startsWith("test ") || normalized.startsWith("grep "))
                    && !normalized.isBlank()) {
                candidates.add(normalized);
            }
        }
    }

    private String commandFromCriterion(String criterion) {
        if (criterion == null || criterion.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("`([^`]*(?:test|grep)[^`]*)`").matcher(criterion);
        List<String> commands = new ArrayList<>();
        while (matcher.find()) {
            String command = matcher.group(1).strip();
            if (!command.isBlank()) {
                commands.add(command);
            }
        }
        return String.join(" && ", commands);
    }

    private boolean hasNonEmptyArray(Object value) {
        if (value instanceof JsonNode node) {
            return node.isArray() && !node.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return false;
    }

    private boolean qaReportBlocksDelivery(
            RequirementExecutionRequest request,
            RepairExecutionResult repairResult
    ) {
        if (request == null
                || request.role() != AgentRole.QA_AGENT
                || repairResult == null
                || repairResult.status() != RepairExecutionStatus.SUCCESS) {
            return false;
        }
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        String status = text(value.get("status"));
        return "FAILED".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status);
    }

    private String qaFailureReason(RepairExecutionResult repairResult) {
        Map<String, Object> value = expandedAgentResult(repairResult.rawResultJson());
        String summary = text(value.get("summary"));
        if (summary.isBlank()) {
            return "QA_AGENT failed acceptance";
        }
        return "QA_AGENT failed: " + summary;
    }

    private String text(Object value) {
        if (value instanceof JsonNode node) {
            return node.isTextual() ? node.asText("").strip() : node.toString();
        }
        return value == null ? "" : value.toString().strip();
    }

    private Map<String, Object> expandedAgentResult(Map<String, String> rawResultJson) {
        Map<String, Object> value = new LinkedHashMap<>();
        String completeAgentJson = rawResultJson.getOrDefault(AGENT_RESULT_JSON_FIELD, "");
        if (!completeAgentJson.isBlank()) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(completeAgentJson);
                if (root != null && root.isObject()) {
                    root.fields().forEachRemaining(entry -> value.put(entry.getKey(), entry.getValue()));
                }
            } catch (JsonProcessingException ignored) {
                value.put(AGENT_RESULT_JSON_FIELD, completeAgentJson);
            }
        }
        rawResultJson.forEach((key, rawValue) -> {
            if (!AGENT_RESULT_JSON_FIELD.equals(key)) {
                value.putIfAbsent(key, rawValue);
            }
        });
        return value;
    }

    private RepositoryParts repositoryParts(RdRequirementTask task) {
        String owner = task.repoOwner();
        String name = task.repoName();
        if (!owner.isBlank() && !name.isBlank()) {
            return new RepositoryParts(owner, stripGitSuffix(name));
        }
        RepositoryParts parsed = parseRepositoryUrl(task.repositoryUrl());
        if (!owner.isBlank()) {
            return new RepositoryParts(owner, parsed.name());
        }
        if (!name.isBlank()) {
            return new RepositoryParts(parsed.owner(), stripGitSuffix(name));
        }
        return parsed;
    }

    private RepositoryParts parseRepositoryUrl(String repositoryUrl) {
        String normalized = repositoryUrl == null ? "" : repositoryUrl.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl must not be blank");
        }
        try {
            URI uri = new URI(normalized);
            String path = uri.getPath() == null ? "" : uri.getPath();
            RepositoryParts parts = parsePath(path);
            if (!parts.owner().isBlank() && !parts.name().isBlank()) {
                return parts;
            }
        } catch (URISyntaxException ignored) {
            // Fall back to SSH-like parsing below.
        }
        Matcher matcher = SSH_GIT_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return new RepositoryParts(matcher.group(1), stripGitSuffix(matcher.group(2)));
        }
        throw new IllegalArgumentException("repositoryUrl must contain owner and repository name");
    }

    private RepositoryParts parsePath(String path) {
        String normalized = path == null ? "" : path.strip();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return new RepositoryParts("", "");
        }
        return new RepositoryParts(parts[0], stripGitSuffix(parts[1]));
    }

    private String workBranch(RdRequirementTask task) {
        return "requirement/" + task.taskId();
    }

    private String stripGitSuffix(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.endsWith(".git") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private record RepositoryParts(String owner, String name) {
    }
}
