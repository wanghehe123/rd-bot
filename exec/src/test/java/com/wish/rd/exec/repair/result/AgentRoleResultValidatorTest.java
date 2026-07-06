package com.wish.rd.exec.repair.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;

class AgentRoleResultValidatorTest {

    private final AgentRoleResultValidator validator = new AgentRoleResultValidator();

    @Test
    void shouldAcceptRequirementReviewResultWithFeasibilityAndCoverage() {
        AgentRoleResultValidation validation = validator.validate("REQUIREMENT_REVIEWER", """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "risks": ["低风险"],
                  "acceptanceCoverage": [
                    {"criteria": "接口测试通过", "covered": true}
                  ]
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldRejectRequirementReviewResultWithoutFeasibility() {
        AgentRoleResultValidation validation = validator.validate("REQUIREMENT_REVIEWER", """
                {
                  "decision": "APPROVED",
                  "missingInformation": [],
                  "risks": [],
                  "acceptanceCoverage": []
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("feasibility must not be blank"));
    }

    @Test
    void shouldRejectSolutionPlanWithoutAcceptanceMapping() {
        AgentRoleResultValidation validation = validator.validate("SOLUTION_ARCHITECT", """
                {
                  "summary": "实现订单催单",
                  "affectedFiles": ["src/main/java/OrderController.java"],
                  "implementationSteps": ["增加接口"],
                  "testPlan": ["./mvnw test"]
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("acceptanceMapping must be a non-empty array"));
    }

    @Test
    void shouldRejectQaReportWhenAcceptanceResultHasNoRealCommandOrLog() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "验收通过",
                  "acceptanceResults": [
                    {"criteria": "接口测试通过", "status": "PASSED"}
                  ]
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("acceptanceResults[0].command must not be blank"));
        assertTrue(validation.errors().contains("acceptanceResults[0].logArtifactId must not be blank"));
    }

    @Test
    void shouldRejectQaReportWhenOverallPassedButOneAcceptanceFailed() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "部分命令失败但错误标成通过",
                  "acceptanceResults": [
                    {
                      "criteria": "文档存在",
                      "command": "test -s docs/rd-bot.md",
                      "status": "PASSED",
                      "logArtifactId": "artifact://logs/1"
                    },
                    {
                      "criteria": "负向命令必须失败",
                      "command": "false",
                      "status": "FAILED",
                      "logArtifactId": "artifact://logs/2"
                    }
                  ]
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("status PASSED requires all acceptanceResults to be PASSED"));
    }

    @Test
    void shouldAcceptQaReportWhenOverallFailedAndFailedAcceptanceIsRecorded() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "FAILED",
                  "summary": "负向命令失败，阻断交付",
                  "acceptanceResults": [
                    {
                      "criteria": "文档存在",
                      "command": "test -s docs/rd-bot.md",
                      "status": "PASSED",
                      "logArtifactId": "artifact://logs/1"
                    },
                    {
                      "criteria": "负向命令必须失败",
                      "command": "false",
                      "status": "FAILED",
                      "logArtifactId": "artifact://logs/2"
                    }
                  ]
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldDelegateCodingAgentResultToStructuredRepairValidator() {
        AgentRoleResultValidation validation = validator.validate("CODING_AGENT", """
                {
                  "status": "SUCCESS",
                  "summary": "已实现订单催单",
                  "changedFiles": ["src/main/java/OrderController.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "prBody": "实现说明",
                  "needHumanAction": false
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }
}
