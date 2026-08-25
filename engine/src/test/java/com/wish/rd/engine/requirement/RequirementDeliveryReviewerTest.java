package com.wish.rd.engine.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.engine.requirement.model.RequirementDeliveryReviewResult;

class RequirementDeliveryReviewerTest {

    private final RequirementDeliveryReviewer reviewer = new RequirementDeliveryReviewer();

    @Test
    void shouldApproveDeliveryWhenAllRolesSucceededAndCodingEvidenceIsPresent() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                successfulDeliveryJson()
        );

        assertTrue(result.approved());
        assertTrue(result.toJson().contains("\"approved\":true"));
    }

    @Test
    void shouldRejectDeliveryWhenQaStageIsMissing() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                """
                        {
                          "status": "SUCCESS",
                          "multiAgentStatus": "SUCCESS",
                          "multiAgentStages": [
                            {"role":"REQUIREMENT_REVIEWER","success":true},
                            {"role":"SOLUTION_ARCHITECT","success":true},
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}}
                          ]
                        }
                        """
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("QA_AGENT"));
    }

    @Test
    void shouldRejectDeliveryWhenCodingEvidenceIsIncomplete() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                """
                        {
                          "status": "SUCCESS",
                          "multiAgentStatus": "SUCCESS",
                          "multiAgentStages": [
                            {"role":"REQUIREMENT_REVIEWER","success":true},
                            {"role":"SOLUTION_ARCHITECT","success":true},
                            {"role":"CODING_AGENT","success":true,"resultJson":{"status":"SUCCESS"}},
                            {"role":"QA_AGENT","success":true,"resultJson":{}}
                          ]
                        }
                        """
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("CODING_AGENT"));
    }

    @Test
    void shouldRejectDeliveryWhenQaEvidenceIsIncomplete() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                """
                        {
                          "status": "SUCCESS",
                          "multiAgentStatus": "SUCCESS",
                          "multiAgentStages": [
                            {"role":"REQUIREMENT_REVIEWER","success":true},
                            {"role":"SOLUTION_ARCHITECT","success":true},
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                            {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","summary":"验收通过"}}
                          ]
                        }
                        """
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("QA_AGENT"));
    }

    @Test
    void shouldRejectDeliveryWhenQaAcceptanceResultDidNotPass() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                """
                        {
                          "status": "SUCCESS",
                          "multiAgentStatus": "SUCCESS",
                          "multiAgentStages": [
                            {"role":"REQUIREMENT_REVIEWER","success":true},
                            {"role":"SOLUTION_ARCHITECT","success":true},
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                            {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","summary":"验收通过","acceptanceResults":[{"criteria":"接口测试通过","command":"./mvnw test","status":"FAILED","logArtifactId":"artifact-1"}]}}
                          ]
                        }
                        """
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("QA_AGENT"));
    }

    @Test
    void shouldRejectLegacyQaPassWithoutCurrentAndRegressionEvidence() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                """
                        {
                          "status": "SUCCESS",
                          "multiAgentStatus": "SUCCESS",
                          "multiAgentStages": [
                            {"role":"REQUIREMENT_REVIEWER","success":true},
                            {"role":"SOLUTION_ARCHITECT","success":true},
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                            {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","summary":"验收通过","acceptanceResults":[{"criteria":"接口测试通过","command":"./mvnw test","status":"PASSED","logArtifactId":"artifact-1"}]}}
                          ]
                        }
                        """
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("QA_AGENT"));
    }

    @Test
    void shouldRejectDeliveryWhenAgentStageContainsPullRequestUrl() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                """
                        {
                          "status": "SUCCESS",
                          "multiAgentStatus": "SUCCESS",
                          "multiAgentStages": [
                            {"role":"REQUIREMENT_REVIEWER","success":true},
                            {"role":"SOLUTION_ARCHITECT","success":true},
                            {"role":"CODING_AGENT","success":true,"pullRequestUrl":"https://github.com/acme/order/pull/9","resultJson":{"prBody":"body","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                            {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","summary":"验收通过","acceptanceResults":[{"criteria":"接口测试通过","command":"./mvnw test","status":"PASSED","logArtifactId":"artifact-1"}]}}
                          ]
                        }
                        """
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("pullRequestUrl"));
    }

    @Test
    void shouldRejectDeliveryWhenResultJsonIsMalformed() {
        RequirementDeliveryReviewResult result = reviewer.review(
                "task-1",
                "{not-json"
        );

        assertFalse(result.approved());
        assertTrue(result.reason().contains("invalid delivery result json"));
    }

    @Test
    void shouldRejectEmptyChangedFilesAndMissingTestCommandsWithFieldReason() {
        RequirementDeliveryReviewResult emptyFiles = reviewer.review(
                "task-1", successfulDeliveryJson().replace("[\"src/App.java\"]", "[]"));
        RequirementDeliveryReviewResult missingCommands = reviewer.review(
                "task-1", successfulDeliveryJson().replace("\"testCommands\":[\"./mvnw test\"],", ""));

        assertFalse(emptyFiles.approved());
        assertTrue(emptyFiles.reason().contains("changedFiles"));
        assertFalse(missingCommands.approved());
        assertTrue(missingCommands.reason().contains("testCommands"));
    }

    @Test
    void shouldRejectRiskOutsideProductionContract() {
        RequirementDeliveryReviewResult invalidRisk = reviewer.review(
                "task-1", successfulDeliveryJson().replace("\"riskLevel\":\"LOW\"", "\"riskLevel\":\"whatever\""));

        assertFalse(invalidRisk.approved());
        assertTrue(invalidRisk.reason().contains("riskLevel"));
    }

    @Test
    void shouldRejectQaAcceptanceMissingExitCodeOrNotPassed() {
        RequirementDeliveryReviewResult missingExitCode = reviewer.review(
                "task-1", successfulDeliveryJson().replace("\"exitCode\":0,", ""));
        RequirementDeliveryReviewResult failed = reviewer.review(
                "task-1", successfulDeliveryJson().replaceFirst("\"status\":\"PASSED\",\"exitCode\"", "\"status\":\"FAILED\",\"exitCode\""));

        assertFalse(missingExitCode.approved());
        assertTrue(missingExitCode.reason().contains("exitCode"));
        assertFalse(failed.approved());
        assertTrue(failed.reason().contains("status"));
    }

    @Test
    void shouldRejectFractionalExitCodeAndDuration() {
        RequirementDeliveryReviewResult fractionalExit = reviewer.review(
                "task-1", successfulDeliveryJson().replaceFirst("\"exitCode\":0", "\"exitCode\":0.5"));
        RequirementDeliveryReviewResult fractionalDuration = reviewer.review(
                "task-1", successfulDeliveryJson().replaceFirst("\"durationMillis\":120", "\"durationMillis\":1.5"));

        assertFalse(fractionalExit.approved());
        assertTrue(fractionalExit.reason().contains("exitCode"));
        assertFalse(fractionalDuration.approved());
        assertTrue(fractionalDuration.reason().contains("durationMillis"));
    }

    @Test
    void shouldRejectRuntimeOnlyLoopbackEvidence() {
        for (String manifest : java.util.List.of(
                "http://2130706433/manifest.json",
                "http://0177.0.0.1/manifest.json",
                "http://0x7f000001/manifest.json",
                "http://0x7f.0.0.1/manifest.json",
                "http://0.0.0.0/manifest.json",
                "http://[::]/manifest.json")) {
            String runtimeOnly = successfulDeliveryJson()
                    .replace("qa-evidence/manifest.json", manifest)
                    .replace("qa-evidence/commands/current.log", "http://[::ffff:127.0.0.1]/current.log")
                    .replace("qa-evidence/commands/regression.log", "http://localhost/regression.log");

            RequirementDeliveryReviewResult result = reviewer.review("task-1", runtimeOnly);

            assertFalse(result.approved());
            assertTrue(result.reason().contains("persistent"), result.reason());
        }
    }

    private static String successfulDeliveryJson() {
        return """
                {
                  "status": "SUCCESS",
                  "multiAgentStatus": "SUCCESS",
                  "multiAgentStages": [
                    {"role":"REQUIREMENT_REVIEWER","success":true},
                    {"role":"SOLUTION_ARCHITECT","success":true},
                    {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"## Summary\\n- implement","changedFiles":["src/App.java"],"testCommands":["./mvnw test"],"testStatus":"PASSED","riskLevel":"LOW"}},
                    {"role":"QA_AGENT","success":true,"resultJson":{
                      "status":"PASSED",
                      "summary":"当前需求和回归验收通过",
                      "failureCategory":"NONE",
                      "retryRecommendation":"NONE",
                      "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                      "acceptanceResults":[
                        {"criteria":"前端构建通过","scope":"CURRENT","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":120,"logArtifactId":"qa-evidence/commands/current.log","evidenceArtifactIds":["qa-evidence/commands/current.log"]},
                        {"criteria":"既有接口回归","scope":"REGRESSION","command":"./mvnw test","status":"PASSED","exitCode":0,"durationMillis":120,"logArtifactId":"qa-evidence/commands/regression.log","evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                      ],
                      "evidenceManifestArtifactId":"qa-evidence/manifest.json"
                    }}
                  ]
                }
                """;
    }
}
