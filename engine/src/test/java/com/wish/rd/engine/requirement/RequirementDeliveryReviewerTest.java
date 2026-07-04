package com.wish.rd.engine.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body"}}
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
                            {"role":"QA_AGENT","success":true}
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
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body","changedFiles":"src/App.java","testSummary":"./mvnw test passed"}},
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
                            {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"body","changedFiles":"src/App.java","testSummary":"./mvnw test passed"}},
                            {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","summary":"验收通过","acceptanceResults":[{"criteria":"接口测试通过","command":"./mvnw test","status":"FAILED","logArtifactId":"artifact-1"}]}}
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
                            {"role":"CODING_AGENT","success":true,"pullRequestUrl":"https://github.com/acme/order/pull/9","resultJson":{"prBody":"body","changedFiles":"src/App.java","testSummary":"./mvnw test passed"}},
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

    private static String successfulDeliveryJson() {
        return """
                {
                  "status": "SUCCESS",
                  "multiAgentStatus": "SUCCESS",
                  "multiAgentStages": [
                    {"role":"REQUIREMENT_REVIEWER","success":true},
                    {"role":"SOLUTION_ARCHITECT","success":true},
                    {"role":"CODING_AGENT","success":true,"resultJson":{"prBody":"## Summary\\n- implement","changedFiles":"src/App.java","testSummary":"./mvnw test passed"}},
                    {"role":"QA_AGENT","success":true,"resultJson":{"status":"PASSED","summary":"验收通过","acceptanceResults":[{"criteria":"前端构建通过","command":"./mvnw test","status":"PASSED","logArtifactId":"artifact-qa-log"}]}}
                  ]
                }
                """;
    }
}
