package com.wish.rd.exec.repair.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;

class AgentRoleResultValidatorTest {

    private final AgentRoleResultValidator validator = new AgentRoleResultValidator();

    @Test
    void shouldAcceptPiV2QaExplicitCodingRequestForAnyFailureCategory() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status":"FAILED","summary":"product bug observed during environment setup",
                  "failureCategory":"ENVIRONMENT","retryRecommendation":"CODING_AGENT",
                  "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                  "acceptanceResults":[
                    {"criteriaId":"ac-current-1","criteria":"current","scope":"CURRENT","command":"npm test","status":"FAILED","exitCode":1,"durationMillis":10,"logArtifactId":"qa-evidence/commands/current.log","evidenceArtifactIds":["qa-evidence/commands/current.log"]},
                    {"criteriaId":"ac-regression-1","criteria":"regression","scope":"REGRESSION","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":10,"logArtifactId":"qa-evidence/commands/regression.log","evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                  ],
                  "evidenceManifestArtifactId":"qa-evidence/manifest.json",
                  "remediationRequest":{"requested":true,"targetRole":"CODING_AGENT","reason":"Fix the checkout bug","bugFindingIds":["bug-1"]},
                  "bugFindings":[{"id":"bug-1","severity":"HIGH","acceptanceCriteriaId":"ac-current-1","reproductionSteps":["run npm test"],"expected":"checkout succeeds","actual":"checkout fails","evidenceArtifactIds":["qa-evidence/commands/current.log"],"suspectedFiles":["src/checkout.ts"]}]
                }
                """, true);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldRejectContradictoryPiV2QaRemediationRequest() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status":"PASSED","summary":"passed but forged remediation",
                  "failureCategory":"NONE","retryRecommendation":"NONE",
                  "browserValidation":{"required":false,"performed":false,"decisionSource":"NOT_APPLICABLE","baseUrl":"","browser":"chromium","viewports":[]},
                  "acceptanceResults":[
                    {"criteriaId":"ac-current-1","criteria":"current","scope":"CURRENT","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":10,"logArtifactId":"qa-evidence/commands/current.log","evidenceArtifactIds":["qa-evidence/commands/current.log"]},
                    {"criteriaId":"ac-regression-1","criteria":"regression","scope":"REGRESSION","command":"npm test","status":"PASSED","exitCode":0,"durationMillis":10,"logArtifactId":"qa-evidence/commands/regression.log","evidenceArtifactIds":["qa-evidence/commands/regression.log"]}
                  ],
                  "evidenceManifestArtifactId":"qa-evidence/manifest.json",
                  "remediationRequest":{"requested":true,"targetRole":"CODING_AGENT","reason":"forged","bugFindingIds":["missing"]},
                  "bugFindings":[]
                }
                """, true);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("requires status FAILED")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("unknown bug finding")));
    }

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
                  ],
                  "budgetEstimate": {
                    "initialTokens": 80000,
                    "retryReserveTokens": 20000,
                    "estimatedTotalTokens": 100000,
                    "confidence": "MEDIUM",
                    "basis": "基于四角色交付范围和历史实际样本",
                    "historicalSamples": []
                  }
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
    void shouldRejectRequirementReviewResultWithoutBudgetEstimate() {
        AgentRoleResultValidation validation = validator.validate("REQUIREMENT_REVIEWER", """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "risks": [],
                  "acceptanceCoverage": ["接口测试通过"]
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("budgetEstimate must be an object"));
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
    void shouldRejectQaReportWithoutStrictEvidenceContract() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "legacy QA result",
                  "acceptanceResults": [
                    {
                      "criteria": "接口测试通过",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "logArtifactId": "qa-evidence/commands/current-1.log"
                    }
                  ]
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("failureCategory must not be blank"));
        assertTrue(validation.errors().contains("retryRecommendation must not be blank"));
        assertTrue(validation.errors().contains("browserValidation must be an object"));
        assertTrue(validation.errors().contains("evidenceManifestArtifactId must not be blank"));
        assertTrue(validation.errors().contains("acceptanceResults[0].scope must not be blank"));
        assertTrue(validation.errors().contains("acceptanceResults[0].exitCode must be an integer"));
        assertTrue(validation.errors().contains("acceptanceResults[0].durationMillis must be a non-negative integer"));
        assertTrue(validation.errors().contains("acceptanceResults[0].evidenceArtifactIds must be a non-empty array"));
    }

    @Test
    void shouldRejectPassedQaResultWithNonZeroExitCode() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "错误地忽略了命令退出码",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": false,
                    "performed": false,
                    "decisionSource": "NOT_APPLICABLE",
                    "baseUrl": "",
                    "browser": "chromium",
                    "viewports": []
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "当前功能",
                      "scope": "CURRENT",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 1,
                      "durationMillis": 100,
                      "logArtifactId": "qa-evidence/commands/current-1.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current-1.log"]
                    },
                    {
                      "criteria": "回归测试",
                      "scope": "REGRESSION",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 100,
                      "logArtifactId": "qa-evidence/commands/regression-1.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression-1.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "acceptanceResults[0].status PASSED requires exitCode 0"));
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
                  "failureCategory": "PRODUCT_DEFECT",
                  "retryRecommendation": "CODING_AGENT",
                  "browserValidation": {
                    "required": false,
                    "performed": false,
                    "decisionSource": "NOT_APPLICABLE",
                    "baseUrl": "",
                    "browser": "chromium",
                    "viewports": []
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "文档存在",
                      "scope": "CURRENT",
                      "command": "test -s docs/rd-bot.md",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 5,
                      "logArtifactId": "qa-evidence/commands/current-1.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current-1.log"]
                    },
                    {
                      "criteria": "负向命令必须失败",
                      "scope": "REGRESSION",
                      "command": "false",
                      "status": "FAILED",
                      "exitCode": 1,
                      "durationMillis": 5,
                      "logArtifactId": "qa-evidence/commands/regression-1.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression-1.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldAcceptStrictPassedQaReportWithCurrentAndRegressionEvidence() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "当前功能和回归均通过",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": true,
                    "performed": true,
                    "decisionSource": "PROJECT_PROFILE",
                    "baseUrl": "http://127.0.0.1:4173",
                    "browser": "chromium",
                    "viewports": ["desktop-1440x900", "mobile-390x844"]
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "新增筛选流程",
                      "scope": "CURRENT",
                      "command": "bash /work/output/qa-work/current-flow.sh",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1200,
                      "logArtifactId": "qa-evidence/commands/current-1.log",
                      "evidenceArtifactIds": ["qa-evidence/screenshots/current-1.png"]
                    },
                    {
                      "criteria": "原有创建流程",
                      "scope": "REGRESSION",
                      "command": "bash /work/output/qa-work/regression-flow.sh",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 900,
                      "logArtifactId": "qa-evidence/commands/regression-1.log",
                      "evidenceArtifactIds": ["qa-evidence/screenshots/regression-1.png"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldRejectRequiredBrowserQaWithoutPinnedBrowserAndBothViewports() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "browser checks passed",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": true,
                    "performed": true,
                    "decisionSource": "PROJECT_PROFILE",
                    "baseUrl": "http://127.0.0.1:4173",
                    "browser": "firefox",
                    "viewports": ["desktop-1440x900"]
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "current flow",
                      "scope": "CURRENT",
                      "command": "bash current.sh",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 10,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/screenshots/current.png"]
                    },
                    {
                      "criteria": "regression flow",
                      "scope": "REGRESSION",
                      "command": "bash regression.sh",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 10,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/screenshots/regression.png"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("browserValidation.browser must be chromium"));
        assertTrue(validation.errors().contains(
                "browserValidation.viewports must include mobile-390x844"));
    }

    @Test
    void shouldRejectAgentControlledHostAssertionsAndMalformedEchoes() {
        AgentRoleResultValidation validation = validator.validate("QA_AGENT", """
                {
                  "status": "PASSED",
                  "summary": "all checks passed",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": false,
                    "performed": false,
                    "decisionSource": "NOT_APPLICABLE",
                    "baseUrl": "",
                    "browser": "chromium",
                    "viewports": []
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "current",
                      "scope": "CURRENT",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                    },
                    {
                      "criteria": "regression",
                      "scope": "REGRESSION",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json",
                  "hostAssertionBundle": {"specs": []},
                  "hostAssertionWorkspace": "/tmp/agent-selected",
                  "hostAssertionResults": [
                    {
                      "scope": "CURRENT",
                      "contentHash": "not-a-hash",
                      "evidenceArtifactIds": [],
                      "specs": []
                    }
                  ]
                }
                """);

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("hostAssertionBundle is not accepted")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("hostAssertionWorkspace is not accepted")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("hostAssertionResults[0] may contain only")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("contentHash must be a sha256")));
        assertTrue(validation.errors().stream().anyMatch(error -> error.contains("evidenceArtifactIds must be a non-empty array")));
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
