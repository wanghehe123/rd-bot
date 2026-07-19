package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskFailureDiagnostic;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFailureDiagnosticParserTest {

    private final TaskFailureDiagnosticParser parser = new TaskFailureDiagnosticParser();

    @Test
    void mapsRequirementNeedInfoIntoReadableIssuesRisksAndAcceptanceGaps() {
        TaskFailureDiagnostic diagnostic = parser.parse(
                TaskFailurePhase.AGENT_ROLE,
                AgentRole.REQUIREMENT_REVIEWER,
                "",
                "Docker Claude Code role result.",
                """
                        {
                          "decision":"NEED_INFO",
                          "feasibility":"CAN_DO",
                          "missingInformation":["确认顾客测试账号","锁定频控 HTTP 状态码"],
                          "risks":["订单数据层方案未收敛"],
                          "acceptanceCoverage":{"missing":["商家订单列表端点"]}
                        }
                        """
        );

        assertTrue(diagnostic.requiresSupplement());
        assertEquals("需求信息不足", diagnostic.title());
        assertEquals(List.of("确认顾客测试账号", "锁定频控 HTTP 状态码"),
                diagnostic.issues().stream().map(issue -> issue.detail()).toList());
        assertEquals("订单数据层方案未收敛", diagnostic.risks().getFirst().detail());
        assertEquals("商家订单列表端点", diagnostic.acceptanceGaps().getFirst().detail());
    }

    @Test
    void fallsBackToSafeGenericDiagnosticForMalformedHistoricalJson() {
        TaskFailureDiagnostic diagnostic = parser.parse(
                TaskFailurePhase.AGENT_ROLE,
                AgentRole.CODING_AGENT,
                "AGENT_EXECUTOR_EXCEPTION",
                "container stopped",
                "{not-json"
        );

        assertFalse(diagnostic.title().isBlank());
        assertFalse(diagnostic.summary().isBlank());
        assertFalse(diagnostic.suggestedAction().isBlank());
    }
}
