package com.wish.rd.engine;

import com.wish.rd.engine.bugfix.acceptance.AcceptancePlan;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanGenerationCommand;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanStatus;
import com.wish.rd.engine.bugfix.acceptance.RagEvidenceAcceptancePlanGenerator;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptancePlanGeneratorTest {

    @Test
    void shouldGenerateReadyWaimaiOrderAcceptancePlanFromRagEvidence() {
        RagEvidenceAcceptancePlanGenerator generator = new RagEvidenceAcceptancePlanGenerator();

        AcceptancePlan plan = generator.generate(new AcceptancePlanGenerationCommand(
                "task-waimai-1",
                waimaiOrderMessage()
        )).plan();

        assertEquals("task-waimai-1", plan.taskId());
        assertEquals("FI-waimai-acceptance", plan.ticketId());
        assertEquals(AcceptancePlanStatus.READY, plan.status());
        assertEquals("rd-bot-rag-evidence", plan.source());
        assertEquals(List.of(
                "waimai#client/src/api.ts#createOrder",
                "waimai#server/src/routes/orders.ts#post"
        ), plan.evidenceChunkIds());
        assertFalse(plan.steps().isEmpty());
        assertTrue(plan.steps().stream().anyMatch(step -> step.description().contains("POST /api/orders")));
        assertTrue(plan.steps().stream().anyMatch(step -> step.description().contains("client/src/api.ts")));
        assertTrue(plan.assertions().stream().anyMatch(assertion -> assertion.expected().contains("address")));
        assertTrue(plan.assertions().stream().anyMatch(assertion -> assertion.expected().contains("customer_name")));
        assertTrue(plan.toPromptSection().contains("RD-Bot 是最终验收裁判"));
    }

    @Test
    void shouldReturnUnsupportedWhenRagEvidenceIsMissing() {
        RagEvidenceAcceptancePlanGenerator generator = new RagEvidenceAcceptancePlanGenerator();

        AcceptancePlan plan = generator.generate(new AcceptancePlanGenerationCommand(
                "task-empty",
                new BugFixMessage(
                        "ticket-empty",
                        "未知接口 500",
                        "缺少可用 RAG 证据",
                        List.of(),
                        "task-empty",
                        false,
                        "",
                        "",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        "",
                        false
                )
        )).plan();

        assertEquals(AcceptancePlanStatus.UNSUPPORTED, plan.status());
        assertEquals("missing RAG evidence for acceptance planning", plan.reason());
    }

    private static BugFixMessage waimaiOrderMessage() {
        return new BugFixMessage(
                "FI-waimai-acceptance",
                "外卖下单接口返回 500",
                "顾客下单时前端 createOrder 字段与服务端 POST /api/orders 不一致。",
                List.of("waimai", "orders"),
                "task-waimai-1",
                false,
                "waimai",
                "外卖下单接口",
                "ANSWER",
                "",
                List.of("IntentDirectedVectorSearch", "CodeRepositorySearch"),
                List.of(
                        new RetrievedChunk(
                                "waimai#client/src/api.ts#createOrder",
                                "client createOrder sends delivery_address and remark",
                                "waimai",
                                "code",
                                "client/src/api.ts",
                                0.98,
                                java.util.Map.of("path", "client/src/api.ts")
                        ),
                        new RetrievedChunk(
                                "waimai#server/src/routes/orders.ts#post",
                                "server POST /api/orders requires address, phone, customer_name and note",
                                "waimai",
                                "code",
                                "server/src/routes/orders.ts",
                                0.97,
                                java.util.Map.of("path", "server/src/routes/orders.ts")
                        )
                ),
                "RAG 命中 waimai 下单接口前后端字段不一致。",
                "",
                "请修复 waimai POST /api/orders 字段不一致。",
                List.of("bug"),
                List.of(
                        "waimai#client/src/api.ts#createOrder",
                        "waimai#server/src/routes/orders.ts#post"
                ),
                "字段不一致导致 500。",
                false
        );
    }
}
