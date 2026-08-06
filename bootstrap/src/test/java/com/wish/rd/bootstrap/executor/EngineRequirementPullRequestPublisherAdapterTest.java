package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementPullRequestPublisherAdapter;

import com.wish.rd.engine.requirement.model.RequirementPullRequestPublishCommand;
import com.wish.rd.engine.requirement.model.RequirementPullRequestPublication;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementPullRequestPublisherAdapterTest {

    @Test
    void shouldCreatePullRequestFromReviewedDeliveryResult() {
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("https://github.com/acme/order/pull/42");
        EngineRequirementPullRequestPublisherAdapter adapter =
                new EngineRequirementPullRequestPublisherAdapter(codePlatform);

        RequirementPullRequestPublication publication = adapter.publish(command("sha256:op-test-1"));

        assertTrue(publication.success());
        assertEquals("https://github.com/acme/order/pull/42", publication.pullRequestUrl());
        assertEquals(1, codePlatform.findOpenCalls.get());
        assertEquals("RD-Bot requirement: 需求交付", codePlatform.command().title());
        assertEquals("acme", codePlatform.command().repoOwner());
        assertEquals("order", codePlatform.command().repoName());
        assertEquals("main", codePlatform.command().baseBranch());
        assertEquals("requirement/task-1001", codePlatform.command().workBranch());
        assertTrue(codePlatform.command().prBody().contains("implement requirement"));
        assertTrue(codePlatform.command().prBody().contains("deliveryReview"));
        assertTrue(codePlatform.command().prBody().contains("approved"));
        assertTrue(codePlatform.command().prBody().contains("RD-Bot QA Evidence"));
        assertTrue(codePlatform.command().prBody().contains("真实验收通过"));
        assertTrue(codePlatform.command().prBody().contains("acceptanceResults=1"));
        assertTrue(codePlatform.command().prBody().contains("taskId: task-1001"));
        assertTrue(codePlatform.command().prBody().contains("operationId: sha256:op-test-1"));
        assertTrue(codePlatform.command().prBody().contains("rd-artifact://task-1001/coding/patch.diff"));
        assertEquals("task-1001", codePlatform.command().metadata().get("taskId"));
        assertEquals("sha256:op-test-1", codePlatform.command().metadata().get("operationId"));
        assertEquals("REQUIREMENT", codePlatform.command().metadata().get("taskType"));
        assertEquals("true", codePlatform.command().metadata().get("deliveryReviewApproved"));
        assertEquals("main", codePlatform.command().metadata().get("targetBranch"));
        assertEquals("requirement/task-1001", codePlatform.command().metadata().get("workBranch"));
        assertEquals("1", codePlatform.command().metadata().get("qaAcceptanceResultCount"));
        assertEquals("true", codePlatform.command().metadata().get("prBodyIncludesDeliveryReview"));
        assertEquals("true", codePlatform.command().metadata().get("prBodyIncludesQaEvidence"));
        assertEquals("true", codePlatform.command().metadata().get("prBodyContainsArtifactLink"));
        assertEquals("true", codePlatform.command().metadata().get("prBodyContainsOperationId"));
        assertEquals("true", codePlatform.command().metadata().get("prBodyEvidenceIncluded"));
        assertTrue(publication.metadataJson().contains("\"deliveryReviewApproved\":\"true\""));
        assertTrue(publication.metadataJson().contains("\"qaAcceptanceResultCount\":\"1\""));
        assertTrue(publication.metadataJson().contains("\"prBodyContainsArtifactLink\":\"true\""));
        assertTrue(publication.metadataJson().contains("\"prBodyEvidenceIncluded\":\"true\""));
    }

    @Test
    void shouldReuseOpenPullRequestWhenTaskAndOperationMarkersMatch() {
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("https://github.com/acme/order/pull/42");
        codePlatform.openPullRequest = new PullRequestResult(
                "https://github.com/acme/order/pull/77",
                "77",
                Map.of(
                        "provider", "recording",
                        "body", "- taskId: task-1001\n- operationId: sha256:op-reuse"
                )
        );
        EngineRequirementPullRequestPublisherAdapter adapter =
                new EngineRequirementPullRequestPublisherAdapter(codePlatform);

        RequirementPullRequestPublication publication = adapter.publish(command("sha256:op-reuse"));

        assertTrue(publication.success());
        assertEquals("https://github.com/acme/order/pull/77", publication.pullRequestUrl());
        assertEquals("77", publication.pullRequestNumber());
        assertNull(codePlatform.command);
        assertEquals(1, codePlatform.findOpenCalls.get());
        assertTrue(publication.metadataJson().contains("\"reusedOpenPullRequest\":\"true\""));
    }

    @Test
    void shouldRejectOpenPullRequestWhenMarkersDoNotMatch() {
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("https://github.com/acme/order/pull/42");
        codePlatform.openPullRequest = new PullRequestResult(
                "https://github.com/acme/order/pull/77",
                "77",
                Map.of("body", "- taskId: other-task\n- operationId: sha256:other")
        );
        EngineRequirementPullRequestPublisherAdapter adapter =
                new EngineRequirementPullRequestPublisherAdapter(codePlatform);

        RequirementPullRequestPublication publication = adapter.publish(command("sha256:op-mismatch"));

        assertEquals(false, publication.success());
        assertTrue(publication.errorMessage().contains("markers do not match"));
        assertNull(codePlatform.command);
    }

    @Test
    void shouldRejectBlankPullRequestUrl() {
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("");
        EngineRequirementPullRequestPublisherAdapter adapter =
                new EngineRequirementPullRequestPublisherAdapter(codePlatform);

        RequirementPullRequestPublication publication = adapter.publish(command("sha256:op-blank"));

        assertEquals(false, publication.success());
        assertTrue(publication.errorMessage().contains("blank pull request url"));
    }

    private RequirementPullRequestPublishCommand command(String operationId) {
        return new RequirementPullRequestPublishCommand(
                "task-1001",
                "需求交付",
                "https://github.com/acme/order.git",
                "",
                "",
                "main",
                "requirement/task-1001",
                """
                        {
                          "status": "SUCCESS",
                          "summary": "实现完成",
                          "prBody": "## Summary\\n- implement requirement",
                          "changedFiles": "src/main/java/com/example/OrderController.java",
                          "testSummary": "./mvnw test passed",
                          "multiAgentStatus": "SUCCESS",
                          "deliveryReview": {
                            "reviewer": "DELIVERY_REVIEWER",
                            "approved": true
                          },
                          "stageResults": [
                            {
                              "role": "CODING_AGENT",
                              "result": {
                                "summary": "编码完成",
                                "stageArtifacts": [
                                  {
                                    "type": "PATCH_DIFF",
                                    "uri": "rd-artifact://task-1001/coding/patch.diff",
                                    "summary": "patch diff"
                                  }
                                ]
                              }
                            },
                            {
                              "role": "QA_AGENT",
                              "result": {
                                "summary": "真实验收通过",
                                "acceptanceResults": [
                                  {
                                    "criteria": "四个 Agent 阶段必须真实落库",
                                    "command": "./mvnw test",
                                    "status": "PASSED",
                                    "logArtifactId": "9001"
                                  }
                                ]
                              }
                            }
                          ],
                          "roleResults": {
                            "QA_AGENT": {
                              "summary": "真实验收通过",
                              "acceptanceResults": [
                                {
                                  "criteria": "QA 阶段必须记录真实验收结果",
                                  "command": "./mvnw -pl bootstrap test",
                                  "status": "PASSED",
                                  "logArtifactId": "9002"
                                }
                              ]
                            }
                          }
                        }
                        """,
                operationId
        );
    }

    private static final class RecordingCodePlatform implements CodePlatformPort {

        private final String pullRequestUrl;
        private CreatePullRequestCommand command;
        private PullRequestResult openPullRequest;
        private final AtomicInteger findOpenCalls = new AtomicInteger();

        private RecordingCodePlatform(String pullRequestUrl) {
            this.pullRequestUrl = pullRequestUrl;
        }

        @Override
        public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
            this.command = command;
            return new PullRequestResult(pullRequestUrl, "42", Map.of("provider", "recording"));
        }

        @Override
        public Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
            findOpenCalls.incrementAndGet();
            return Optional.ofNullable(openPullRequest);
        }

        private CreatePullRequestCommand command() {
            return command;
        }
    }
}
