package com.wish.rd.rag.prompt;

import com.wish.rd.framework.convention.model.ChatMessage;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.guidance.model.GuidanceDecision;
import com.wish.rd.rag.intent.model.IntentLevel;
import com.wish.rd.rag.intent.model.IntentNode;
import com.wish.rd.rag.intent.model.NodeScore;
import com.wish.rd.rag.pipeline.model.RepairContextPackage;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;
import com.wish.rd.rag.rewrite.model.QueryTermMapping;
import com.wish.rd.rag.rewrite.impl.RuleBasedQueryRewriteService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.prompt.model.PromptScene;
import com.wish.rd.rag.prompt.model.RepairPromptPlan;

class RepairPromptServiceTest {

    @Test
    void rewritesSplitsAndBuildsRepairPromptPlanFromRetrievedEvidence() {
        RuleBasedQueryRewriteService rewriteService = new RuleBasedQueryRewriteService(List.of(
                new QueryTermMapping("下单", "POST /api/orders", 10, true),
                new QueryTermMapping("金额", "orders.amount", 9, true)
        ));
        RepairPromptService promptService = RepairPromptService.defaultService(rewriteService);

        RepairRagRequest request = new RepairRagRequest(
                "ticket-1",
                "支付系统下单接口 500。金额为空怎么修复？",
                List.of("traceId=t-1")
        );
        IntentNode intent = IntentNode.builder()
                .id("payment-system")
                .name("支付系统")
                .description("支付、下单、订单、金额")
                .level(IntentLevel.SYSTEM)
                .knowledgeBaseIds(List.of("payment-system"))
                .codeRepositoryIds(List.of("payment-service"))
                .build();
        RepairContextPackage contextPackage = new RepairContextPackage(
                "ticket-1",
                Optional.of(new NodeScore(intent, 9.5d, List.of("支付", "orders.amount"))),
                GuidanceDecision.none(),
                List.of(
                        new RetrievedChunk(
                                "kb-1",
                                "POST /api/orders 写入订单前必须校验 orders.amount",
                                "payment-system",
                                "api",
                                "payment-api.md",
                                8.0d,
                                Map.of()
                        ),
                        new RetrievedChunk(
                                "log-1",
                                "ERROR OrderService.create orders.amount is null",
                                "payment-system",
                                "runtime-log",
                                "log-center",
                                7.0d,
                                Map.of()
                        ),
                        new RetrievedChunk(
                                "code-1",
                                "OrderService.create should validate amount before repository.save",
                                "payment-system",
                                "code-snippet",
                                "OrderService.java",
                                6.0d,
                                Map.of()
                        )
                ),
                List.of("IntentDirectedVectorSearch", "LogCenterSearch", "CodeRepositorySearch"),
                "summary"
        );

        RepairPromptPlan plan = promptService.build(request, contextPackage);

        assertEquals(PromptScene.REPAIR_MIXED, plan.scene());
        assertTrue(plan.rewrite().rewrittenQuestion().contains("POST /api/orders"));
        assertTrue(plan.rewrite().rewrittenQuestion().contains("orders.amount"));
        assertEquals(2, plan.rewrite().subQuestions().size());
        assertTrue(plan.systemPrompt().contains("研发修复机器人"));
        assertTrue(plan.userPrompt().contains("【知识库证据】"));
        assertTrue(plan.userPrompt().contains("【运行日志】"));
        assertTrue(plan.userPrompt().contains("【代码证据】"));
        assertTrue(plan.userPrompt().contains("【拆分问题】"));
        assertTrue(plan.userPrompt().contains("OrderService.create"));
        assertEquals(List.of("kb-1", "log-1", "code-1"), plan.evidenceChunkIds());
    }

    @Test
    void includesConversationMemoryWhenHistoryIsProvided() {
        RepairPromptService promptService = RepairPromptService.defaultService(RuleBasedQueryRewriteService.empty());
        RepairRagRequest request = new RepairRagRequest("ticket-2", "刚才说的是哪个字段？", List.of());
        RepairContextPackage contextPackage = new RepairContextPackage(
                "ticket-2",
                Optional.empty(),
                GuidanceDecision.none(),
                List.of(new RetrievedChunk(
                        "kb-2",
                        "orders.amount 为空会导致下单失败",
                        "payment-system",
                        "api",
                        "payment-api.md",
                        8.0d,
                        Map.of()
                )),
                List.of("IntentDirectedVectorSearch"),
                "summary"
        );

        RepairPromptPlan plan = promptService.build(
                request,
                contextPackage,
                List.of(
                        ChatMessage.user("支付系统下单接口 500"),
                        ChatMessage.assistant("已经定位到 OrderService.create 缺少 orders.amount 校验")
                )
        );

        assertTrue(plan.promptSections().contains("对话记忆"));
        assertTrue(plan.userPrompt().contains("【对话记忆】"));
        assertTrue(plan.userPrompt().contains("支付系统下单接口 500"));
        assertTrue(plan.userPrompt().contains("orders.amount 校验"));
    }
}
