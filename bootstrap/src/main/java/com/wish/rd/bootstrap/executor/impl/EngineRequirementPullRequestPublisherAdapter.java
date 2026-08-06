package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.RequirementPullRequestPublisherPort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将已复核的需求交付结果发布到代码平台 PR。
 */
public final class EngineRequirementPullRequestPublisherAdapter implements RequirementPullRequestPublisherPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SSH_GIT_PATTERN = Pattern.compile("[:/]([^/:]+)/([^/]+?)(?:\\.git)?$");
    private static final Pattern ARTIFACT_URI_PATTERN = Pattern.compile(
            "(?:https?://|s3://|rd-artifact://)[^\\s)\\]}>,\"']+"
    );

    private final CodePlatformPort codePlatform;

    public EngineRequirementPullRequestPublisherAdapter(CodePlatformPort codePlatform) {
        this.codePlatform = Objects.requireNonNull(codePlatform, "codePlatform must not be null");
    }

    @Override
    public RequirementPullRequestPublication publish(RequirementPullRequestPublishCommand command) {
        if (command == null) {
            return RequirementPullRequestPublication.failure("", "publish command must not be null");
        }
        RepositoryParts repository = repositoryParts(command);
        String operationId = command.operationId();
        String prBody = pullRequestBody(command, operationId);
        Map<String, String> requestMetadata = metadata(command, prBody, operationId);
        Optional<PullRequestResult> existing = codePlatform.findOpenPullRequest(new FindOpenPullRequestCommand(
                repository.owner(),
                repository.name(),
                command.baseBranch(),
                command.workBranch()
        ));
        if (existing.isPresent()) {
            PullRequestResult openPull = existing.get();
            String openBody = safe(openPull.metadata().get("body"));
            if (markersMatch(openBody, command.taskId(), operationId)) {
                Map<String, String> reused = new LinkedHashMap<>(requestMetadata);
                reused.put("reusedOpenPullRequest", "true");
                openPull.metadata().forEach(reused::putIfAbsent);
                return RequirementPullRequestPublication.success(
                        command.taskId(),
                        openPull.pullRequestUrl(),
                        openPull.pullRequestNumber(),
                        metadataJson(reused)
                );
            }
            return RequirementPullRequestPublication.failure(
                    command.taskId(),
                    "open pull request exists for head/base but taskId/operationId markers do not match"
            );
        }
        PullRequestResult result = codePlatform.createPullRequest(new CreatePullRequestCommand(
                repository.owner(),
                repository.name(),
                command.baseBranch(),
                command.workBranch(),
                "RD-Bot requirement: " + command.title(),
                prBody,
                List.of(),
                requestMetadata
        ));
        if (result == null || result.pullRequestUrl().isBlank()) {
            return RequirementPullRequestPublication.failure(command.taskId(), "code platform returned blank pull request url");
        }
        return RequirementPullRequestPublication.success(
                command.taskId(),
                result.pullRequestUrl(),
                result.pullRequestNumber(),
                metadataJson(publicationMetadata(requestMetadata, result.metadata()))
        );
    }

    private Map<String, String> metadata(
            RequirementPullRequestPublishCommand command,
            String prBody,
            String operationId
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("taskId", command.taskId());
        metadata.put("operationId", operationId);
        metadata.put("taskType", "REQUIREMENT");
        metadata.put("targetBranch", command.baseBranch());
        metadata.put("workBranch", command.workBranch());
        metadata.put("deliveryReviewApproved", Boolean.toString(deliveryReviewApproved(command.deliveryResultJson())));
        metadata.put("qaAcceptanceResultCount", Integer.toString(qaAcceptanceResultCount(command.deliveryResultJson())));
        metadata.put(
                "prBodyIncludesDeliveryReview",
                Boolean.toString(safe(prBody).contains("RD-Bot Delivery Review"))
        );
        metadata.put(
                "prBodyIncludesQaEvidence",
                Boolean.toString(safe(prBody).contains("RD-Bot QA Evidence"))
        );
        metadata.put(
                "prBodyContainsTaskId",
                Boolean.toString(!command.taskId().isBlank() && safe(prBody).contains(command.taskId()))
        );
        metadata.put(
                "prBodyContainsOperationId",
                Boolean.toString(!operationId.isBlank() && safe(prBody).contains(operationId))
        );
        metadata.put(
                "prBodyContainsArtifactLink",
                Boolean.toString(containsArtifactLink(prBody))
        );
        metadata.put(
                "prBodyEvidenceIncluded",
                Boolean.toString(safe(prBody).contains("RD-Bot Delivery Review")
                        && safe(prBody).contains("RD-Bot QA Evidence")
                        && !command.taskId().isBlank()
                        && safe(prBody).contains(command.taskId())
                        && containsArtifactLink(prBody))
        );
        return Map.copyOf(metadata);
    }

    private boolean markersMatch(String prBody, String taskId, String operationId) {
        String body = safe(prBody);
        if (taskId.isBlank() || !body.contains("taskId: " + taskId)) {
            return false;
        }
        if (operationId.isBlank()) {
            return true;
        }
        return body.contains("operationId: " + operationId);
    }

    private Map<String, String> publicationMetadata(
            Map<String, String> requestMetadata,
            Map<String, String> resultMetadata
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (requestMetadata != null) {
            metadata.putAll(requestMetadata);
        }
        if (resultMetadata != null) {
            resultMetadata.forEach(metadata::putIfAbsent);
        }
        return Map.copyOf(metadata);
    }

    private boolean deliveryReviewApproved(String deliveryResultJson) {
        try {
            return OBJECT_MAPPER.readTree(deliveryResultJson).path("deliveryReview").path("approved").asBoolean(false);
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private String pullRequestBody(RequirementPullRequestPublishCommand command, String operationId) {
        String deliveryResultJson = command.deliveryResultJson();
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(deliveryResultJson);
        } catch (JsonProcessingException exception) {
            return "## RD-Bot Delivery\n\n" + safe(deliveryResultJson);
        }
        String prBody = text(root.path("prBody"));
        String summary = firstNonBlank(text(root.path("summary")), "RD-Bot requirement delivery");
        String changedFiles = text(root.path("changedFiles"));
        String testSummary = text(root.path("testSummary"));
        String deliveryReview = "{\"deliveryReview\":" + nodeJson(root.path("deliveryReview")) + "}";
        QaEvidence qaEvidence = qaEvidence(root);
        List<String> artifactLinks = artifactLinks(root);
        String base = prBody.isBlank()
                ? "## Summary\n" + summary
                : prBody;
        return """
                %s

                ## RD-Bot Delivery Review
                ```json
                %s
                ```

                ## RD-Bot QA Evidence
                - summary: %s
                - acceptanceResults=%d

                ## Evidence
                - taskId: %s
                - operationId: %s
                - changedFiles: %s
                - testSummary: %s

                ## RD-Bot Artifact Links
                %s
                """.formatted(
                base,
                deliveryReview,
                qaEvidence.summary().isBlank() ? "not reported" : qaEvidence.summary(),
                qaEvidence.acceptanceResultCount(),
                command.taskId(),
                operationId.isBlank() ? "not-reported" : operationId,
                changedFiles.isBlank() ? "not reported" : changedFiles,
                testSummary.isBlank() ? "not reported" : testSummary,
                artifactLinkSummary(artifactLinks)
        ).strip();
    }

    private int qaAcceptanceResultCount(String deliveryResultJson) {
        try {
            return qaEvidence(OBJECT_MAPPER.readTree(deliveryResultJson)).acceptanceResultCount();
        } catch (JsonProcessingException exception) {
            return 0;
        }
    }

    private QaEvidence qaEvidence(JsonNode root) {
        JsonNode qaResult = qaResult(root);
        if (qaResult.isMissingNode() || qaResult.isNull()) {
            return new QaEvidence("", 0);
        }
        JsonNode acceptanceResults = qaResult.path("acceptanceResults");
        return new QaEvidence(text(qaResult.path("summary")), acceptanceResults.isArray() ? acceptanceResults.size() : 0);
    }

    private JsonNode qaResult(JsonNode root) {
        JsonNode multiAgentStages = root.path("multiAgentStages");
        if (multiAgentStages.isArray()) {
            for (JsonNode stage : multiAgentStages) {
                if ("QA_AGENT".equals(text(stage.path("role")))) {
                    return parseObject(text(stage.path("resultJson")));
                }
            }
        }
        JsonNode stages = root.path("stageResults");
        if (stages.isArray()) {
            for (JsonNode stage : stages) {
                if ("QA_AGENT".equals(text(stage.path("role")))) {
                    JsonNode result = stage.path("result");
                    return result.isObject() ? result : parseObject(text(stage.path("resultJson")));
                }
            }
        }
        JsonNode roleResult = root.path("roleResults").path("QA_AGENT");
        return roleResult.isObject() ? roleResult : OBJECT_MAPPER.missingNode();
    }

    private JsonNode parseObject(String value) {
        if (value.isBlank()) {
            return OBJECT_MAPPER.missingNode();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(value);
            return parsed.isObject() ? parsed : OBJECT_MAPPER.missingNode();
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.missingNode();
        }
    }

    private List<String> artifactLinks(JsonNode root) {
        Set<String> links = new LinkedHashSet<>();
        collectArtifactLinks(root, links);
        return List.copyOf(links);
    }

    private void collectArtifactLinks(JsonNode node, Set<String> links) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            Matcher matcher = ARTIFACT_URI_PATTERN.matcher(node.asText(""));
            while (matcher.find()) {
                links.add(matcher.group());
            }
            return;
        }
        if (node.isObject() || node.isArray()) {
            node.forEach(child -> collectArtifactLinks(child, links));
        }
    }

    private String artifactLinkSummary(List<String> artifactLinks) {
        if (artifactLinks == null || artifactLinks.isEmpty()) {
            return "- artifact: not reported";
        }
        List<String> lines = new ArrayList<>();
        for (String artifactLink : artifactLinks) {
            lines.add("- artifact: " + artifactLink);
        }
        return String.join("\n", lines);
    }

    private boolean containsArtifactLink(String prBody) {
        return ARTIFACT_URI_PATTERN.matcher(safe(prBody)).find();
    }

    private String nodeJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String metadataJson(Map<String, String> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private RepositoryParts repositoryParts(RequirementPullRequestPublishCommand command) {
        String owner = command.repoOwner();
        String name = command.repoName();
        if (!owner.isBlank() && !name.isBlank()) {
            return new RepositoryParts(owner, stripGitSuffix(name));
        }
        RepositoryParts parsed = parseRepositoryUrl(command.repositoryUrl());
        if (!owner.isBlank()) {
            return new RepositoryParts(owner, parsed.name());
        }
        if (!name.isBlank()) {
            return new RepositoryParts(parsed.owner(), stripGitSuffix(name));
        }
        return parsed;
    }

    private RepositoryParts parseRepositoryUrl(String repositoryUrl) {
        String normalized = safe(repositoryUrl);
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
        String normalized = safe(path);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return new RepositoryParts("", "");
        }
        return new RepositoryParts(parts[0], stripGitSuffix(parts[1]));
    }

    private String stripGitSuffix(String value) {
        String normalized = safe(value);
        return normalized.endsWith(".git") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String text(JsonNode node) {
        return node == null || !node.isTextual() ? "" : safe(node.asText());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record RepositoryParts(String owner, String name) {
    }

    private record QaEvidence(String summary, int acceptanceResultCount) {

        private QaEvidence {
            summary = safe(summary);
            acceptanceResultCount = Math.max(acceptanceResultCount, 0);
        }
    }
}
