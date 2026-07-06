package com.wish.rd.engine.testflow;

import com.wish.rd.framework.convention.model.ChatMessage;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.DocumentIngestionService;
import com.wish.rd.rag.intent.model.IntentLevel;
import com.wish.rd.rag.intent.model.IntentNode;
import com.wish.rd.rag.intent.IntentTree;
import com.wish.rd.rag.memory.ConversationMemoryService;
import com.wish.rd.rag.memory.impl.DefaultConversationMemoryService;
import com.wish.rd.rag.pipeline.model.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;
import com.wish.rd.rag.prompt.model.RepairPromptPlan;
import com.wish.rd.rag.prompt.RepairPromptService;
import com.wish.rd.rag.rewrite.model.QueryTermMapping;
import com.wish.rd.rag.rewrite.impl.RuleBasedQueryRewriteService;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.wish.rd.engine.testflow.model.RagChatFlowTestResult;

/**
 * 聊天流程测试引擎：对应 {@code POST /test/rag/chat-flow}。
 *
 * <p>演示多轮对话中"对话记忆"的加载与追加：跑两轮确定性对话，
 * 验证第二轮能加载到第一轮的用户/助手历史，并在 Prompt 中体现"对话记忆"段落。
 *
 * <p>流程：
 * <ol>
 *   <li>第一轮：加载历史（空）→ 追加用户问题 → RAG 检索 → Prompt 规划 → 确定性回答 → 追加助手消息；</li>
 *   <li>第二轮：加载历史（含第一轮两轮消息）→ 追加追问 → 用同一上下文再规划 Prompt；</li>
 *   <li>返回两轮回答、第二轮的 Prompt 段落与用户 Prompt、以及最终记忆中的消息总数。</li>
 * </ol>
 */
public final class RagChatFlowTestEngine {

    /** 测试用固定会话 ID。 */
    private static final String CONVERSATION_ID = "conversation-memory-test";
    /** 测试用固定用户 ID。 */
    private static final String USER_ID = "test-user";

    private final ConversationMemoryService memoryService;

    public RagChatFlowTestEngine() {
        this(DefaultConversationMemoryService.inMemory());
    }

    public RagChatFlowTestEngine(ConversationMemoryService memoryService) {
        this.memoryService = memoryService == null ? DefaultConversationMemoryService.inMemory() : memoryService;
    }

    public RagChatFlowTestResult run() {
        // Prompt 规划器，携带两条术语映射
        RepairPromptService promptService = RepairPromptService.defaultService(new RuleBasedQueryRewriteService(List.of(
                new QueryTermMapping("下单", "POST /api/orders", 10, true),
                new QueryTermMapping("金额", "orders.amount", 9, true)
        )));
        RepairRagPipeline pipeline = repairPipeline();

        // 第一轮：加载历史（此时为空）并追加用户问题
        RepairRagRequest firstRequest = new RepairRagRequest(
                "ticket-chat-flow-1",
                "支付系统下单接口 500。金额为空怎么修复？",
                List.of("traceId=t-1")
        );
        List<ChatMessage> firstHistory = memoryService.loadAndAppend(
                CONVERSATION_ID,
                USER_ID,
                ChatMessage.user(firstRequest.description())
        );
        RepairContextPackage firstContext = pipeline.prepareContext(firstRequest);
        RepairPromptPlan firstPrompt = promptService.build(firstRequest, firstContext, firstHistory);
        String firstAnswer = deterministicAnswer(firstPrompt);
        // 把第一轮助手回答写回记忆
        memoryService.append(CONVERSATION_ID, USER_ID, ChatMessage.assistant(firstAnswer));

        // 第二轮：此时历史已含第一轮的用户问题与助手回答
        RepairRagRequest secondRequest = new RepairRagRequest(
                "ticket-chat-flow-2",
                "刚才说的是哪个字段？",
                List.of()
        );
        List<ChatMessage> secondHistory = memoryService.loadAndAppend(
                CONVERSATION_ID,
                USER_ID,
                ChatMessage.user(secondRequest.description())
        );
        // 第二轮复用第一轮的检索上下文，重点验证"对话记忆"段落已包含历史
        RepairPromptPlan secondPrompt = promptService.build(secondRequest, firstContext, secondHistory);
        String secondAnswer = "字段是 orders.amount。";
        memoryService.append(CONVERSATION_ID, USER_ID, ChatMessage.assistant(secondAnswer));

        return new RagChatFlowTestResult(
                CONVERSATION_ID,
                firstAnswer,
                secondAnswer,
                secondPrompt.promptSections(),
                secondPrompt.userPrompt(),
                memoryService.load(CONVERSATION_ID, USER_ID).size()
        );
    }

    /** 装配修复管线：种入支付文档、构建意图树、注入 mock 日志/代码端口。 */
    private RepairRagPipeline repairPipeline() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        DocumentIngestionService.inMemory(vectorStore).write(new DocumentIngestionCommand(
                "payment-api.md",
                "payment-system",
                "api",
                "text/markdown",
                """
                # 支付系统 API

                POST /api/orders 是下单接口。
                OrderService.create 写入订单前必须校验 orders.amount。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                96,
                12
        ));
        IntentTree intentTree = new IntentTree(List.of(
                IntentNode.builder()
                        .id("payment-system")
                        .name("支付系统")
                        .description("支付、下单、订单、金额、orders.amount")
                        .level(IntentLevel.SYSTEM)
                        .systemId("payment-system")
                        .knowledgeBaseIds(List.of("payment-system"))
                        .codeRepositoryIds(List.of("payment-service"))
                        .examples(List.of("支付系统下单接口 500", "金额为空"))
                        .build()
        ));
        return RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTree,
                query -> List.of("ERROR OrderService.create orders.amount is null"),
                query -> List.of(new RetrievedChunk(
                        "payment-service#OrderService.java#42",
                        "OrderService.create should validate amount before repository.save",
                        "payment-system",
                        "code-snippet",
                        "OrderService.java",
                        9.0d,
                        Map.of("repositoryId", query.repositoryId())
                )),
                context -> {}
        );
    }

    /** MVP 确定性回答：命中 orders.amount 给定位结论，否则要求补充上下文。 */
    private String deterministicAnswer(RepairPromptPlan promptPlan) {
        if (promptPlan.userPrompt().contains("orders.amount")) {
            return "已经定位到 OrderService.create 缺少 orders.amount 校验。";
        }
        return "需要补充更多上下文。";
    }
}
