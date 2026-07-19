package com.wish.rd.engine.requirement;

import com.wish.rd.engine.requirement.model.RequirementContextPackage;
import com.wish.rd.engine.requirement.model.RequirementPolicyDecision;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleBasedRequirementPolicyGateTest {

    private final RuleBasedRequirementPolicyGate policyGate = new RuleBasedRequirementPolicyGate();

    @Test
    void shouldRequireApprovalForJwtTokenAcceptanceInsteadOfRejectingItAsUnsafe() {
        RequirementPolicyDecision decision = policyGate.decide(
                task(List.of("POST /api/auth/login 获取 customer token")),
                context(),
                null,
                List.of()
        );

        assertEquals("WAITING_APPROVAL", decision.action());
        assertEquals("HIGH", decision.riskLevel());
        assertEquals("需求涉及高风险模块，等待人工审批", decision.reason());
    }

    @Test
    void shouldRequireApprovalForTokenReferenceWithoutAuthenticationKeyword() {
        RequirementPolicyDecision decision = policyGate.decide(
                task(List.of("接口响应包含 reminder token 字段")),
                context(),
                null,
                List.of()
        );

        assertEquals("WAITING_APPROVAL", decision.action());
        assertEquals("HIGH", decision.riskLevel());
    }

    @Test
    void shouldKeepExplicitKeyExportUnsafe() {
        RequirementPolicyDecision decision = policyGate.decide(
                task(List.of("导出密钥并写入本地文件")),
                context(),
                null,
                List.of()
        );

        assertEquals("UNSAFE", decision.action());
        assertEquals("HIGH", decision.riskLevel());
    }

    private RdRequirementTask task(List<String> acceptanceCriteria) {
        return RdRequirementTask.created(
                "task-policy-gate",
                new CreateRequirementTaskCommand(
                        "增加订单催单功能",
                        "P2",
                        "https://github.com/example/waimai.git",
                        "example",
                        "waimai",
                        "main",
                        "顾客可以催单",
                        acceptanceCriteria,
                        false
                ),
                1L
        );
    }

    private RequirementContextPackage context() {
        return new RequirementContextPackage(
                "task-policy-gate",
                "订单催单需求",
                List.of("接口可用"),
                List.of(),
                List.of(),
                List.of(),
                "trace-policy-gate"
        );
    }
}
