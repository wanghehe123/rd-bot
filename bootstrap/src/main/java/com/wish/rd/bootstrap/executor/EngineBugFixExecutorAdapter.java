package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.bugfix.BugFixExecutionRequest;
import com.wish.rd.engine.bugfix.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Engine Bug 修复执行端口到 exec 执行器和代码平台端口的 bootstrap 桥接适配器。
 */
public class EngineBugFixExecutorAdapter implements BugFixExecutor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RepairExecutorPort repairExecutor;
    private final CodePlatformPort codePlatform;
    private final RepositoryConfig repositoryConfig;

    /**
     * 创建可直接测试的桥接适配器。
     *
     * @param repairExecutor   修复执行端口
     * @param codePlatform     代码平台端口
     * @param repositoryConfig 仓库配置
     */
    public EngineBugFixExecutorAdapter(
            RepairExecutorPort repairExecutor,
            CodePlatformPort codePlatform,
            RepositoryConfig repositoryConfig
    ) {
        this.repairExecutor = Objects.requireNonNull(repairExecutor, "repairExecutor must not be null");
        this.codePlatform = Objects.requireNonNull(codePlatform, "codePlatform must not be null");
        this.repositoryConfig = Objects.requireNonNull(repositoryConfig, "repositoryConfig must not be null");
    }

    @Override
    public BugFixExecutionResult execute(BugFixExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RepairJobCommand repairCommand = toRepairCommand(request);
        RepairExecutionResult repairResult = repairExecutor.execute(repairCommand);
        if (repairResult == null) {
            return new BugFixExecutionResult(
                    request.taskId(),
                    ticketTitle(request.ragMessage()),
                    "",
                    "",
                    toResultJson(failedResult("repair executor returned null"), "", null)
            );
        }

        String pullRequestUrl = "";
        PullRequestResult pullRequest = null;
        if (repairResult.status() == RepairExecutionStatus.SUCCESS) {
            pullRequest = codePlatform.createPullRequest(toPullRequestCommand(repairCommand, repairResult));
            if (pullRequest == null || pullRequest.pullRequestUrl().isBlank()) {
                throw new IllegalStateException("code platform returned blank pull request url");
            }
            pullRequestUrl = pullRequest.pullRequestUrl();
        }

        return new BugFixExecutionResult(
                repairCommand.taskId(),
                ticketTitle(request.ragMessage()),
                repairResult.summary(),
                pullRequestUrl,
                toResultJson(repairResult, pullRequestUrl, pullRequest)
        );
    }

    private RepairJobCommand toRepairCommand(BugFixExecutionRequest request) {
        BugFixMessage message = request.ragMessage();
        String workBranch = repositoryConfig.workBranchFor(request.taskId());
        return new RepairJobCommand(
                request.taskId(),
                request.taskId(),
                ticketId(message),
                ticketTitle(message),
                request.prompt(),
                repositoryConfig.repositoryUrl(),
                repositoryConfig.repoOwner(),
                repositoryConfig.repoName(),
                repositoryConfig.baseBranch(),
                workBranch,
                contextJson(message),
                Map.of("bridge", "engine-bugfix-executor")
        );
    }

    private CreatePullRequestCommand toPullRequestCommand(RepairJobCommand repairCommand, RepairExecutionResult repairResult) {
        return new CreatePullRequestCommand(
                repositoryConfig.repoOwner(),
                repositoryConfig.repoName(),
                repositoryConfig.baseBranch(),
                repairCommand.workBranch(),
                pullRequestTitle(repairCommand),
                pullRequestBody(repairCommand, repairResult),
                repairResult.artifacts(),
                Map.of(
                        "taskId", repairCommand.taskId(),
                        "repairRecordId", repairCommand.repairRecordId(),
                        "executionStatus", repairResult.status().name()
                )
        );
    }

    private Map<String, String> contextJson(BugFixMessage message) {
        if (message == null) {
            return Map.of();
        }
        Map<String, String> context = new LinkedHashMap<>();
        context.put("ticketId", message.ticketId());
        context.put("ticketTitle", message.ticketTitle());
        context.put("ticketDescription", message.ticketDescription());
        context.put("contextSummary", message.contextSummary());
        context.put("evidenceChunkIds", String.join(",", message.evidenceChunkIds()));
        context.put("answer", message.answer());
        return Map.copyOf(context);
    }

    private String pullRequestTitle(RepairJobCommand repairCommand) {
        if (!repairCommand.ticketTitle().isBlank()) {
            return "RD-Bot repair: " + repairCommand.ticketTitle();
        }
        return "RD-Bot repair: " + repairCommand.taskId();
    }

    private String pullRequestBody(RepairJobCommand repairCommand, RepairExecutionResult repairResult) {
        String structuredPrBody = repairResult.rawResultJson().getOrDefault("prBody", "");
        if (!structuredPrBody.isBlank()) {
            return structuredPrBody;
        }
        return """
                ## Summary
                %s

                ## Task
                - taskId: %s
                - ticketId: %s

                ## Artifacts
                %s
                """.formatted(
                repairResult.summary(),
                repairCommand.taskId(),
                repairCommand.ticketId(),
                artifactsSummary(repairResult.artifacts())
        ).strip();
    }

    private String artifactsSummary(List<RepairArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return "- none";
        }
        return artifacts.stream()
                .map(artifact -> "- %s: %s".formatted(artifact.name(), artifact.uri()))
                .toList()
                .stream()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- none");
    }

    private String toResultJson(
            RepairExecutionResult repairResult,
            String pullRequestUrl,
            PullRequestResult pullRequest
    ) {
        Map<String, Object> resultJson = new LinkedHashMap<>(repairResult.rawResultJson());
        resultJson.putIfAbsent("status", repairResult.status().name());
        if (!repairResult.summary().isBlank()) {
            resultJson.putIfAbsent("summary", repairResult.summary());
        }
        resultJson.put("pullRequestUrl", pullRequestUrl);
        resultJson.put("dockerMetadata", repairResult.dockerMetadataJson());
        resultJson.put("codePlatformMetadata", pullRequest == null ? Map.of() : pullRequest.metadata());
        resultJson.put("executionCodePlatformMetadata", repairResult.githubMetadataJson());
        resultJson.put("testMetadata", repairResult.testMetadataJson());
        resultJson.put("riskMetadata", repairResult.riskMetadataJson());
        resultJson.put("artifacts", repairResult.artifacts().stream()
                .map(this::artifactJson)
                .toList());
        if (!repairResult.errorMessage().isBlank()) {
            resultJson.putIfAbsent("errorMessage", repairResult.errorMessage());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(resultJson);
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"FAILED\",\"errorMessage\":\"failed to serialize execution result\"}";
        }
    }

    private Map<String, Object> artifactJson(RepairArtifact artifact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", artifact.type().name());
        value.put("name", artifact.name());
        value.put("uri", artifact.uri());
        value.put("summary", artifact.summary());
        value.put("metadata", artifact.metadataJson());
        return Map.copyOf(value);
    }

    private RepairExecutionResult failedResult(String errorMessage) {
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                "",
                "",
                List.of(),
                Map.of("status", "FAILED", "errorMessage", errorMessage),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                errorMessage
        );
    }

    private String ticketId(BugFixMessage message) {
        return message == null ? "" : message.ticketId();
    }

    private String ticketTitle(BugFixMessage message) {
        return message == null ? "" : message.ticketTitle();
    }

    /**
     * 桥接适配器仓库配置。
     *
     * @param repoOwner        仓库所属空间
     * @param repoName         仓库名称
     * @param repositoryUrl    仓库 URL
     * @param baseBranch       目标分支
     * @param workBranchPrefix 修复工作分支前缀
     */
    public record RepositoryConfig(
            String repoOwner,
            String repoName,
            String repositoryUrl,
            String baseBranch,
            String workBranchPrefix
    ) {

        public RepositoryConfig {
            repoOwner = requireText(repoOwner, "repoOwner");
            repoName = requireText(repoName, "repoName");
            repositoryUrl = normalize(repositoryUrl);
            baseBranch = requireText(baseBranch, "baseBranch");
            workBranchPrefix = normalize(workBranchPrefix);
            if (workBranchPrefix.isBlank()) {
                workBranchPrefix = "repair/";
            }
        }

        private String workBranchFor(String taskId) {
            String normalizedTaskId = requireText(taskId, "taskId");
            return workBranchPrefix + normalizedTaskId.replaceAll("[^A-Za-z0-9._-]", "-");
        }

        private static String requireText(String value, String fieldName) {
            String normalized = normalize(value);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
