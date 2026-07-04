package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Traceable PR publication evidence collected from a production smoke task detail response.
 */
record PullRequestPublicationEvidence(
        boolean validated,
        boolean targetAllowed,
        boolean bodyEvidenceIncluded,
        int qaAcceptanceResultCount
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    PullRequestPublicationEvidence {
        qaAcceptanceResultCount = Math.max(qaAcceptanceResultCount, 0);
    }

    static PullRequestPublicationEvidence empty() {
        return new PullRequestPublicationEvidence(false, false, false, 0);
    }

    static PullRequestPublicationEvidence from(
            JsonNode detail,
            MultiAgentProductionAcceptanceProfile profile,
            String taskId
    ) {
        try {
            JsonNode executionResult = executionResultJson(detail);
            JsonNode publication = executionResult.path("pullRequestPublication");
            JsonNode metadata = pullRequestMetadata(publication);
            String pullRequestUrl = detail.path("pullRequestUrl").asText("");
            String publicationUrl = publication.path("pullRequestUrl").asText("");
            String workBranch = metadata.path("workBranch").asText("");
            int qaAcceptanceResultCount = metadata.path("qaAcceptanceResultCount").asInt(0);
            boolean taskBound = taskId.equals(metadata.path("taskId").asText(""))
                    && "REQUIREMENT".equals(metadata.path("taskType").asText(""));
            boolean deliveryReviewApproved = booleanValue(metadata.path("deliveryReviewApproved"));
            boolean targetAllowed = profile.baseBranch().equals(metadata.path("targetBranch").asText(""))
                    && !workBranch.isBlank()
                    && !workBranch.equals(profile.baseBranch())
                    && workBranch.equals("requirement/" + taskId);
            boolean bodyEvidenceIncluded = booleanValue(metadata.path("prBodyEvidenceIncluded"))
                    && booleanValue(metadata.path("prBodyIncludesDeliveryReview"))
                    && booleanValue(metadata.path("prBodyIncludesQaEvidence"));
            boolean urlBound = !pullRequestUrl.isBlank() && pullRequestUrl.equals(publicationUrl);
            boolean repositoryAllowed = repositoryAllowed(pullRequestUrl, profile);
            boolean validated = publication.path("success").asBoolean(false)
                    && urlBound
                    && repositoryAllowed
                    && taskBound
                    && deliveryReviewApproved
                    && targetAllowed
                    && bodyEvidenceIncluded
                    && qaAcceptanceResultCount > 0;
            return new PullRequestPublicationEvidence(
                    validated,
                    targetAllowed,
                    bodyEvidenceIncluded,
                    qaAcceptanceResultCount
            );
        } catch (IOException exception) {
            return empty();
        }
    }

    private static JsonNode executionResultJson(JsonNode detail) throws IOException {
        JsonNode value = detail.path("executionResultJson");
        if (value.isObject()) {
            return value;
        }
        String raw = value.asText("{}");
        return raw.isBlank() ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(raw);
    }

    private static JsonNode pullRequestMetadata(JsonNode publication) throws IOException {
        JsonNode metadata = publication.path("metadataJson");
        if (metadata.isObject()) {
            return metadata;
        }
        String raw = metadata.asText("{}");
        return raw.isBlank() ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(raw);
    }

    private static boolean booleanValue(JsonNode node) {
        return node.asBoolean(false) || "true".equalsIgnoreCase(node.asText(""));
    }

    private static boolean repositoryAllowed(String pullRequestUrl, MultiAgentProductionAcceptanceProfile profile) {
        try {
            URI uri = new URI(pullRequestUrl == null ? "" : pullRequestUrl.strip());
            String owner = safePathSegment(profile.repoOwner());
            String repo = safePathSegment(profile.repoName());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().strip();
            String host = uri.getHost() == null ? "" : uri.getHost().strip();
            String repositoryHost = repositoryHost(profile.repositoryUrl());
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String pullRequestPathPattern = "/" + owner + "/" + repo + "/pull/[0-9]+";
            return !owner.isBlank()
                    && !repo.isBlank()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && !host.isBlank()
                    && host.equalsIgnoreCase(repositoryHost)
                    && path.matches(pullRequestPathPattern);
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return false;
        }
    }

    private static String repositoryHost(String repositoryUrl) throws URISyntaxException {
        URI uri = new URI(repositoryUrl == null ? "" : repositoryUrl.strip());
        return uri.getHost() == null ? "" : uri.getHost().strip();
    }

    private static String safePathSegment(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
