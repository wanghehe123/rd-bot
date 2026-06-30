package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.RequirementExecutionResult;
import com.wish.rd.engine.requirement.RequirementExecutorPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import com.wish.rd.rag.runtime.RdRequirementTask;
import com.wish.rd.rag.runtime.TaskMaterial;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求交付执行端口到 exec 执行器和代码平台端口的 bootstrap 桥接适配器。
 */
public final class EngineRequirementExecutorAdapter implements RequirementExecutorPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SSH_GIT_PATTERN = Pattern.compile("[:/]([^/:]+)/([^/]+?)(?:\\.git)?$");

    private final RepairExecutorPort repairExecutor;
    private final CodePlatformPort codePlatform;

    public EngineRequirementExecutorAdapter(
            RepairExecutorPort repairExecutor,
            CodePlatformPort codePlatform
    ) {
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor must not be null");
        this.codePlatform = Objects.requireNonNull(codePlatform, "codePlatform must not be null");
    }

    @Override
    public RequirementExecutionResult execute(RequirementExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RepairJobCommand command = toRepairCommand(request);
        RepairExecutionResult repairResult = repairExecutor.execute(command);
        if (repairResult == null) {
            return RequirementExecutionResult.failure(
                    request.taskId(),
                    "repair executor returned null",
                    "{\"status\":\"FAILED\",\"errorMessage\":\"repair executor returned null\"}"
            );
        }
        PullRequestResult pullRequest = null;
        String pullRequestUrl = "";
        if (repairResult.status() == RepairExecutionStatus.SUCCESS) {
            pullRequest = codePlatform.createPullRequest(toPullRequestCommand(command, repairResult));
            if (pullRequest == null || pullRequest.pullRequestUrl().isBlank()) {
                return RequirementExecutionResult.failure(
                        request.taskId(),
                        "code platform returned blank pull request url",
                        toResultJson(repairResult, "", null)
                );
            }
            pullRequestUrl = pullRequest.pullRequestUrl();
        }
        boolean success = repairResult.status() == RepairExecutionStatus.SUCCESS && !pullRequestUrl.isBlank();
        if (!success) {
            String reason = repairResult.errorMessage().isBlank()
                    ? "requirement execution failed: " + repairResult.status()
                    : repairResult.errorMessage();
            return RequirementExecutionResult.failure(request.taskId(), reason, toResultJson(repairResult, "", null));
        }
        return RequirementExecutionResult.success(
                request.taskId(),
                repairResult.summary(),
                pullRequestUrl,
                toResultJson(repairResult, pullRequestUrl, pullRequest)
        );
    }

    private RepairJobCommand toRepairCommand(RequirementExecutionRequest request) {
        RdRequirementTask task = request.task();
        RepositoryParts repository = repositoryParts(task);
        return new RepairJobCommand(
                request.taskId(),
                request.taskId(),
                "",
                task.title(),
                request.prompt(),
                task.repositoryUrl(),
                repository.owner(),
                repository.name(),
                task.baseBranch(),
                workBranch(task),
                contextJson(request),
                Map.of("bridge", "engine-requirement-executor")
        );
    }

    private CreatePullRequestCommand toPullRequestCommand(
            RepairJobCommand command,
            RepairExecutionResult repairResult
    ) {
        return new CreatePullRequestCommand(
                command.repoOwner(),
                command.repoName(),
                command.baseBranch(),
                command.workBranch(),
                "RD-Bot requirement: " + command.ticketTitle(),
                pullRequestBody(command, repairResult),
                repairResult.artifacts(),
                Map.of(
                        "taskId", command.taskId(),
                        "taskType", "REQUIREMENT",
                        "executionStatus", repairResult.status().name()
                )
        );
    }

    private Map<String, String> contextJson(RequirementExecutionRequest request) {
        Map<String, String> context = new LinkedHashMap<>();
        RdRequirementTask task = request.task();
        context.put("taskId", task.taskId());
        context.put("taskType", task.taskType());
        context.put("title", task.title());
        context.put("expectedResult", task.expectedResult());
        context.put("acceptanceCriteriaJson", task.acceptanceCriteriaJson());
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

    private String pullRequestBody(RepairJobCommand command, RepairExecutionResult repairResult) {
        String structuredPrBody = repairResult.rawResultJson().getOrDefault("prBody", "");
        if (!structuredPrBody.isBlank()) {
            return structuredPrBody;
        }
        return """
                ## Summary
                %s

                ## Requirement Task
                - taskId: %s
                - baseBranch: %s
                - workBranch: %s

                ## Artifacts
                %s
                """.formatted(
                repairResult.summary(),
                command.taskId(),
                command.baseBranch(),
                command.workBranch(),
                artifactsSummary(repairResult.artifacts())
        ).strip();
    }

    private String artifactsSummary(List<RepairArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return "- none";
        }
        return artifacts.stream()
                .map(artifact -> "- %s: %s".formatted(artifact.name(), artifact.uri()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- none");
    }

    private String toResultJson(
            RepairExecutionResult repairResult,
            String pullRequestUrl,
            PullRequestResult pullRequest
    ) {
        Map<String, Object> value = new LinkedHashMap<>(repairResult.rawResultJson());
        value.putIfAbsent("status", repairResult.status().name());
        value.putIfAbsent("summary", repairResult.summary());
        value.put("pullRequestUrl", pullRequestUrl);
        value.put("dockerMetadata", repairResult.dockerMetadataJson());
        value.put("codePlatformMetadata", pullRequest == null ? Map.of() : pullRequest.metadata());
        value.put("testMetadata", repairResult.testMetadataJson());
        value.put("riskMetadata", repairResult.riskMetadataJson());
        if (!repairResult.errorMessage().isBlank()) {
            value.putIfAbsent("errorMessage", repairResult.errorMessage());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"FAILED\",\"errorMessage\":\"failed to serialize execution result\"}";
        }
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
