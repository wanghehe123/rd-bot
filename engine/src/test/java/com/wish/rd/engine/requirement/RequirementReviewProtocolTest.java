package com.wish.rd.engine.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementReviewProtocolTest {

    @Test
    void needInfoAsksOperatorInsteadOfFailingClosed() {
        String json = """
                {
                  "decision": "NEED_INFO",
                  "feasibility": "NEED_INFO",
                  "missingInformation": ["上线窗口"],
                  "status": "NEED_INFO"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.ASK_OPERATOR,
                RequirementReviewProtocol.disposition(json));
        assertTrue(RequirementReviewProtocol.asksOperator(json));
        assertFalse(RequirementReviewProtocol.failsClosed(json));
    }

    @Test
    void rejectedAndUnsafeStillFailClosed() {
        String json = """
                {
                  "decision": "REJECTED",
                  "feasibility": "UNSAFE",
                  "missingInformation": [],
                  "status": "FAILED"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.FAIL_CLOSED,
                RequirementReviewProtocol.disposition(json));
        assertFalse(RequirementReviewProtocol.asksOperator(json));
        assertTrue(RequirementReviewProtocol.failsClosed(json));
    }

    @Test
    void approvedProceeds() {
        String json = """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "status": "SUCCESS"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.PROCEED,
                RequirementReviewProtocol.disposition(json));
        assertFalse(RequirementReviewProtocol.asksOperator(json));
        assertFalse(RequirementReviewProtocol.failsClosed(json));
    }

    @Test
    void approvedWithAdvisoryMissingInformationStillProceeds() {
        String json = """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": ["标记样式未指定（默认次要色文本）"],
                  "status": "SUCCESS"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.PROCEED,
                RequirementReviewProtocol.disposition(json));
        assertFalse(RequirementReviewProtocol.asksOperator(json));
    }

    @Test
    void successStatusDoesNotOverrideNeedInfoDecision() {
        String json = """
                {
                  "status": "SUCCESS",
                  "decision": "NEED_INFO",
                  "missingInformation": ["必需材料"]
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.ASK_OPERATOR,
                RequirementReviewProtocol.disposition(json));
        assertTrue(RequirementReviewProtocol.asksOperator(json));
        assertFalse(RequirementReviewProtocol.failsClosed(json));
    }

    @Test
    void normalizedNeedInfoDecisionAsksOperatorDespiteOkStatus() {
        String json = """
                {
                  "decision": "need-info",
                  "missingInformation": [],
                  "status": "OK"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.ASK_OPERATOR,
                RequirementReviewProtocol.disposition(json));
        assertTrue(RequirementReviewProtocol.asksOperator(json));
    }

    @Test
    void missingInformationWithoutApprovalAsksOperator() {
        String json = """
                {
                  "status": "COMPLETED",
                  "missingInformation": ["缺少部署材料"]
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.ASK_OPERATOR,
                RequirementReviewProtocol.disposition(json));
        assertTrue(RequirementReviewProtocol.asksOperator(json));
    }

    @Test
    void needInfoDecisionWithEmptyMissingInformationStillAsksOperator() {
        String json = """
                {
                  "decision": "NEED_INFO",
                  "missingInformation": []
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.ASK_OPERATOR,
                RequirementReviewProtocol.disposition(json));
        assertTrue(RequirementReviewProtocol.asksOperator(json));
        assertFalse(RequirementReviewProtocol.failsClosed(json));
    }
}
