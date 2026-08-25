package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.RequirementPullRequestPublisherPort;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 engine 已生成并复核的最终正文原样发布到代码平台。
 */
public final class EngineRequirementPullRequestPublisherAdapter implements RequirementPullRequestPublisherPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SSH_GIT_PATTERN = Pattern.compile("[:/]([^/:]+)/([^/]+?)(?:\\.git)?$");
    private static final Pattern ARTIFACT_URI_PATTERN = Pattern.compile(
            "(?:https?://|s3://|rd-artifact://)[^\\s)\\]}>,\"']+"
    );
    private static final Pattern ACCEPTANCE_ROW_PATTERN = Pattern.compile("(?m)^\\|\\s*\\d+\\s*\\|");

    private final CodePlatformPort codePlatform;

    public EngineRequirementPullRequestPublisherAdapter(CodePlatformPort codePlatform) {
        this.codePlatform = Objects.requireNonNull(codePlatform, "codePlatform must not be null");
    }

    @Override
    public RequirementPullRequestPublication publish(RequirementPullRequestPublishCommand command) {
        if (command == null) {
            return RequirementPullRequestPublication.failure("", "publish command must not be null");
        }
        String prBody = safe(command.pullRequestBody());
        if (prBody.isBlank()) {
            return RequirementPullRequestPublication.failure(
                    command.taskId(), "pullRequestBody must not be blank");
        }
        RepositoryParts repository = repositoryParts(command);
        String operationId = command.operationId();
        Map<String, String> requestMetadata = metadata(command, prBody, operationId);
        Optional<PullRequestResult> existing = codePlatform.findOpenPullRequest(new FindOpenPullRequestCommand(
                repository.owner(), repository.name(), command.baseBranch(), command.workBranch()));
        if (existing.isPresent()) {
            PullRequestResult openPull = existing.get();
            String openBody = safe(openPull.metadata().get("body"));
            if (markersMatch(openBody, command.taskId(), operationId)) {
                Map<String, String> reused = new LinkedHashMap<>(requestMetadata);
                reused.put("reusedOpenPullRequest", "true");
                openPull.metadata().forEach(reused::putIfAbsent);
                return RequirementPullRequestPublication.success(
                        command.taskId(), openPull.pullRequestUrl(), openPull.pullRequestNumber(),
                        metadataJson(reused));
            }
            return RequirementPullRequestPublication.failure(
                    command.taskId(),
                    "open pull request exists for head/base but taskId/operationId markers do not match");
        }
        PullRequestResult result = codePlatform.createPullRequest(new CreatePullRequestCommand(
                repository.owner(), repository.name(), command.baseBranch(), command.workBranch(),
                "RD-Bot requirement: " + command.title(), prBody, List.of(), requestMetadata));
        if (result == null || result.pullRequestUrl().isBlank()) {
            return RequirementPullRequestPublication.failure(
                    command.taskId(), "code platform returned blank pull request url");
        }
        return RequirementPullRequestPublication.success(
                command.taskId(), result.pullRequestUrl(), result.pullRequestNumber(),
                metadataJson(publicationMetadata(requestMetadata, result.metadata())));
    }

    private Map<String, String> metadata(
            RequirementPullRequestPublishCommand command,
            String prBody,
            String operationId
    ) {
        boolean deliveryReview = prBody.contains("## Delivery Review")
                && prBody.contains("Approved: **yes**");
        boolean acceptance = prBody.contains("## Acceptance");
        boolean artifactLink = containsArtifactLink(prBody);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("taskId", command.taskId());
        metadata.put("operationId", operationId);
        metadata.put("taskType", "REQUIREMENT");
        metadata.put("targetBranch", command.baseBranch());
        metadata.put("workBranch", command.workBranch());
        metadata.put("deliveryReviewApproved", Boolean.toString(deliveryReview));
        metadata.put("qaAcceptanceResultCount", Integer.toString(acceptanceResultCount(prBody)));
        metadata.put("prBodyIncludesDeliveryReview", Boolean.toString(deliveryReview));
        metadata.put("prBodyIncludesQaEvidence", Boolean.toString(acceptance));
        metadata.put("prBodyContainsTaskId", Boolean.toString(markerPresent(prBody, "taskId", command.taskId())));
        metadata.put("prBodyContainsOperationId",
                Boolean.toString(markerPresent(prBody, "operationId", operationId)));
        metadata.put("prBodyContainsArtifactLink", Boolean.toString(artifactLink));
        metadata.put("prBodyEvidenceIncluded", Boolean.toString(
                deliveryReview && acceptance && markerPresent(prBody, "taskId", command.taskId())
                        && artifactLink));
        return Map.copyOf(metadata);
    }

    private int acceptanceResultCount(String prBody) {
        int count = 0;
        Matcher matcher = ACCEPTANCE_ROW_PATTERN.matcher(prBody);
        while (matcher.find()) count++;
        return count;
    }

    private boolean markersMatch(String prBody, String taskId, String operationId) {
        if (!markerPresent(prBody, "taskId", taskId)) return false;
        return operationId.isBlank() || markerPresent(prBody, "operationId", operationId);
    }

    private boolean markerPresent(String body, String name, String value) {
        if (safe(value).isBlank()) return false;
        String normalized = safe(body);
        return normalized.contains(name + ": " + value)
                || normalized.contains(name + ": `" + value + "`");
    }

    private Map<String, String> publicationMetadata(
            Map<String, String> requestMetadata,
            Map<String, String> resultMetadata
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (requestMetadata != null) metadata.putAll(requestMetadata);
        if (resultMetadata != null) resultMetadata.forEach(metadata::putIfAbsent);
        return Map.copyOf(metadata);
    }

    private boolean containsArtifactLink(String prBody) {
        return ARTIFACT_URI_PATTERN.matcher(safe(prBody)).find();
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
        if (!owner.isBlank() && !name.isBlank()) return new RepositoryParts(owner, stripGitSuffix(name));
        RepositoryParts parsed = parseRepositoryUrl(command.repositoryUrl());
        if (!owner.isBlank()) return new RepositoryParts(owner, parsed.name());
        if (!name.isBlank()) return new RepositoryParts(parsed.owner(), stripGitSuffix(name));
        return parsed;
    }

    private RepositoryParts parseRepositoryUrl(String repositoryUrl) {
        String normalized = safe(repositoryUrl);
        if (normalized.isBlank()) throw new IllegalArgumentException("repositoryUrl must not be blank");
        try {
            URI uri = new URI(normalized);
            RepositoryParts parts = parsePath(uri.getPath() == null ? "" : uri.getPath());
            if (!parts.owner().isBlank() && !parts.name().isBlank()) return parts;
        } catch (URISyntaxException ignored) {
            // Fall back to SSH-like parsing below.
        }
        Matcher matcher = SSH_GIT_PATTERN.matcher(normalized);
        if (matcher.find()) return new RepositoryParts(matcher.group(1), stripGitSuffix(matcher.group(2)));
        throw new IllegalArgumentException("repositoryUrl must contain owner and repository name");
    }

    private RepositoryParts parsePath(String path) {
        String normalized = safe(path);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        String[] parts = normalized.split("/");
        if (parts.length < 2) return new RepositoryParts("", "");
        return new RepositoryParts(parts[0], stripGitSuffix(parts[1]));
    }

    private String stripGitSuffix(String value) {
        String normalized = safe(value);
        return normalized.endsWith(".git") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record RepositoryParts(String owner, String name) {
    }
}
