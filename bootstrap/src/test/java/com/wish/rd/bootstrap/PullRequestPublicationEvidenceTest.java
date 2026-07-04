package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestPublicationEvidenceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldRejectPublicationMetadataWithoutTraceableTaskAndPrBodySections() throws Exception {
        JsonNode detail = OBJECT_MAPPER.readTree("""
                {
                  "pullRequestUrl": "https://github.com/acme/rd-bot-smoke/pull/7",
                  "executionResultJson": {
                    "pullRequestPublication": {
                      "success": true,
                      "pullRequestUrl": "https://github.com/acme/rd-bot-smoke/pull/7",
                      "metadataJson": {
                        "targetBranch": "main",
                        "workBranch": "requirement/task-pr-smoke",
                        "deliveryReviewApproved": true,
                        "qaAcceptanceResultCount": 3,
                        "prBodyEvidenceIncluded": true
                      }
                    }
                  }
                }
                """);

        PullRequestPublicationEvidence evidence =
                PullRequestPublicationEvidence.from(detail, profile(), "task-pr-smoke");

        assertFalse(evidence.validated());
    }

    @Test
    void shouldAcceptPublicationMetadataBoundToTaskAndPrBodyEvidenceSections() throws Exception {
        JsonNode detail = OBJECT_MAPPER.readTree("""
                {
                  "pullRequestUrl": "https://github.com/acme/rd-bot-smoke/pull/7",
                  "executionResultJson": {
                    "pullRequestPublication": {
                      "success": true,
                      "pullRequestUrl": "https://github.com/acme/rd-bot-smoke/pull/7",
                      "metadataJson": {
                        "taskId": "task-pr-smoke",
                        "taskType": "REQUIREMENT",
                        "targetBranch": "main",
                        "workBranch": "requirement/task-pr-smoke",
                        "deliveryReviewApproved": true,
                        "qaAcceptanceResultCount": 3,
                        "prBodyIncludesDeliveryReview": true,
                        "prBodyIncludesQaEvidence": true,
                        "prBodyEvidenceIncluded": true
                      }
                    }
                  }
                }
                """);

        PullRequestPublicationEvidence evidence =
                PullRequestPublicationEvidence.from(detail, profile(), "task-pr-smoke");

        assertTrue(evidence.validated());
        assertTrue(evidence.targetAllowed());
        assertTrue(evidence.bodyEvidenceIncluded());
    }

    @Test
    void shouldRejectPublicationUrlOutsideConfiguredRepository() throws Exception {
        JsonNode detail = OBJECT_MAPPER.readTree("""
                {
                  "pullRequestUrl": "https://github.com/other/rd-bot-smoke/pull/7",
                  "executionResultJson": {
                    "pullRequestPublication": {
                      "success": true,
                      "pullRequestUrl": "https://github.com/other/rd-bot-smoke/pull/7",
                      "metadataJson": {
                        "taskId": "task-pr-smoke",
                        "taskType": "REQUIREMENT",
                        "targetBranch": "main",
                        "workBranch": "requirement/task-pr-smoke",
                        "deliveryReviewApproved": true,
                        "qaAcceptanceResultCount": 3,
                        "prBodyIncludesDeliveryReview": true,
                        "prBodyIncludesQaEvidence": true,
                        "prBodyEvidenceIncluded": true
                      }
                    }
                  }
                }
                """);

        PullRequestPublicationEvidence evidence =
                PullRequestPublicationEvidence.from(detail, profile(), "task-pr-smoke");

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectPublicationUrlOnDifferentHostEvenWhenRepositoryPathMatches() throws Exception {
        JsonNode detail = OBJECT_MAPPER.readTree("""
                {
                  "pullRequestUrl": "https://evil.example/acme/rd-bot-smoke/pull/7",
                  "executionResultJson": {
                    "pullRequestPublication": {
                      "success": true,
                      "pullRequestUrl": "https://evil.example/acme/rd-bot-smoke/pull/7",
                      "metadataJson": {
                        "taskId": "task-pr-smoke",
                        "taskType": "REQUIREMENT",
                        "targetBranch": "main",
                        "workBranch": "requirement/task-pr-smoke",
                        "deliveryReviewApproved": true,
                        "qaAcceptanceResultCount": 3,
                        "prBodyIncludesDeliveryReview": true,
                        "prBodyIncludesQaEvidence": true,
                        "prBodyEvidenceIncluded": true
                      }
                    }
                  }
                }
                """);

        PullRequestPublicationEvidence evidence =
                PullRequestPublicationEvidence.from(detail, profile(), "task-pr-smoke");

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectPublicationUrlWithNonHttpSchemeEvenWhenRepositoryPathMatches() throws Exception {
        JsonNode detail = OBJECT_MAPPER.readTree("""
                {
                  "pullRequestUrl": "ssh://github.com/acme/rd-bot-smoke/pull/7",
                  "executionResultJson": {
                    "pullRequestPublication": {
                      "success": true,
                      "pullRequestUrl": "ssh://github.com/acme/rd-bot-smoke/pull/7",
                      "metadataJson": {
                        "taskId": "task-pr-smoke",
                        "taskType": "REQUIREMENT",
                        "targetBranch": "main",
                        "workBranch": "requirement/task-pr-smoke",
                        "deliveryReviewApproved": true,
                        "qaAcceptanceResultCount": 3,
                        "prBodyIncludesDeliveryReview": true,
                        "prBodyIncludesQaEvidence": true,
                        "prBodyEvidenceIncluded": true
                      }
                    }
                  }
                }
                """);

        PullRequestPublicationEvidence evidence =
                PullRequestPublicationEvidence.from(detail, profile(), "task-pr-smoke");

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectPublicationUrlWithoutPullRequestNumber() throws Exception {
        JsonNode detail = OBJECT_MAPPER.readTree("""
                {
                  "pullRequestUrl": "https://github.com/acme/rd-bot-smoke/pull/",
                  "executionResultJson": {
                    "pullRequestPublication": {
                      "success": true,
                      "pullRequestUrl": "https://github.com/acme/rd-bot-smoke/pull/",
                      "metadataJson": {
                        "taskId": "task-pr-smoke",
                        "taskType": "REQUIREMENT",
                        "targetBranch": "main",
                        "workBranch": "requirement/task-pr-smoke",
                        "deliveryReviewApproved": true,
                        "qaAcceptanceResultCount": 3,
                        "prBodyIncludesDeliveryReview": true,
                        "prBodyIncludesQaEvidence": true,
                        "prBodyEvidenceIncluded": true
                      }
                    }
                  }
                }
                """);

        PullRequestPublicationEvidence evidence =
                PullRequestPublicationEvidence.from(detail, profile(), "task-pr-smoke");

        assertFalse(evidence.validated());
    }

    private static MultiAgentProductionAcceptanceProfile profile() {
        return MultiAgentProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "true"),
                entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.multi-agent.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.multi-agent.smoke.executed-by", "qa-runner"),
                entry("rd.multi-agent.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.multi-agent.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.multi-agent.smoke.postgres-user", "rd_bot"),
                entry("rd.multi-agent.smoke.postgres-password", "secret"),
                entry("rd.multi-agent.smoke.repository-url", "https://github.com/acme/rd-bot-smoke.git"),
                entry("rd.multi-agent.smoke.repo-owner", "acme"),
                entry("rd.multi-agent.smoke.repo-name", "rd-bot-smoke"),
                entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "postgres-secret,github-secret")
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
