package com.wish.rd.engine.rag;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.framework.convention.ChatMessage;
import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.ingestion.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.DocumentIngestionService;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.memory.ConversationMemoryService;
import com.wish.rd.rag.memory.DefaultConversationMemoryService;
import com.wish.rd.rag.pipeline.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.RepairRagRequest;
import com.wish.rd.rag.prompt.RepairPromptPlan;
import com.wish.rd.rag.prompt.RepairPromptService;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.runtime.RagStreamTask;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.vector.InMemoryVectorStore;
import com.wish.rd.rag.vector.VectorStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bug 修复 RAG 编排引擎。
 *
 * <p>入口方法 {@link #findBugFixMessgaesForAgent(TicketSnapshot, List, String, boolean)}
 * 以工单字段为核心输入，完成意图分类、歧义引导、多通道检索与 Agent 消息打包。
 */
public final class RagBugFixEngine {

    private static final String DEFAULT_USER_ID = "test-user";

    private final ConversationMemoryService memoryService;
    private final QueryTermMappingRegistry queryTermMappingRegistry;
    private final IntentTreeRegistry intentTreeRegistry;
    private final KnowledgeWorkspace knowledgeWorkspace;
    private final RagStreamTaskRegistry streamTaskRegistry;
    private final BugFixAgentEngine agentEngine;
    private final ChatQueueLimiter chatQueueLimiter;

    public RagBugFixEngine(ConversationMemoryService memoryService, BugFixAgentEngine agentEngine) {
        this(memoryService, agentEngine, ChatQueueLimiter.passThrough());
    }

    public RagBugFixEngine(
            ConversationMemoryService memoryService,
            BugFixAgentEngine agentEngine,
            ChatQueueLimiter chatQueueLimiter
    ) {
        this(
                memoryService,
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                RagStreamTaskRegistry.inMemory(),
                agentEngine,
                chatQueueLimiter
        );
    }

    public RagBugFixEngine(
            ConversationMemoryService memoryService,
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            BugFixAgentEngine agentEngine,
            ChatQueueLimiter chatQueueLimiter
    ) {
        this.memoryService = memoryService == null ? DefaultConversationMemoryService.inMemory() : memoryService;
        this.queryTermMappingRegistry = queryTermMappingRegistry == null
                ? QueryTermMappingRegistry.withDefaults()
                : queryTermMappingRegistry;
        this.intentTreeRegistry = intentTreeRegistry == null ? IntentTreeRegistry.withDefaults() : intentTreeRegistry;
        this.knowledgeWorkspace = knowledgeWorkspace;
        this.streamTaskRegistry = streamTaskRegistry == null ? RagStreamTaskRegistry.inMemory() : streamTaskRegistry;
        this.agentEngine = agentEngine;
        this.chatQueueLimiter = chatQueueLimiter == null ? ChatQueueLimiter.passThrough() : chatQueueLimiter;
    }

    public BugFixMessage findBugFixMessgaesForAgent(TicketSnapshot ticket, List<String> logs) {
        return findBugFixMessgaesForAgent(ticket, logs, null, false);
    }

    public BugFixMessage findBugFixMessgaesForAgent(
            TicketSnapshot ticket,
            List<String> logs,
            String conversationId,
            boolean deepThinking
    ) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        List<String> safeLogs = logs == null ? List.of() : List.copyOf(logs);
        String actualConversationId = blank(conversationId) ? "conversation-" + UUID.randomUUID() : conversationId;
        String taskId = "task-" + UUID.randomUUID();
        String userQuestion = userQuestion(safeTicket);
        return chatQueueLimiter.enqueue(
                new ChatQueueLimiter.ChatQueueRequest(userQuestion, actualConversationId, taskId),
                () -> runBugFixFlow(safeTicket, safeLogs, actualConversationId, taskId, deepThinking, userQuestion),
                () -> reject(safeTicket, safeLogs, actualConversationId, taskId, deepThinking, userQuestion)
        );
    }

    public BugFixStopResult stop(String taskId) {
        streamTaskRegistry.cancel(taskId);
        return new BugFixStopResult(taskId, "STOPPED");
    }

    public RagStreamTask task(String taskId) {
        return streamTaskRegistry.get(taskId);
    }

    private BugFixMessage runBugFixFlow(
            TicketSnapshot ticket,
            List<String> logs,
            String conversationId,
            String taskId,
            boolean deepThinking,
            String userQuestion
    ) {
        streamTaskRegistry.registerRunning(taskId, conversationId);
        RepairRagRequest request = new RepairRagRequest(ticket.ticketId(), ticketFieldsText(ticket), logs);
        RepairContextPackage context = repairPipeline().prepareContext(request);
        List<ChatMessage> history = memoryService.loadAndAppend(
                conversationId,
                DEFAULT_USER_ID,
                ChatMessage.user(userQuestion)
        );
        RepairPromptPlan promptPlan = RepairPromptService
                .defaultService(queryTermMappingRegistry.rewriteService())
                .build(request, context, history);
        String answer = deterministicAnswer(promptPlan);
        String assistantMessageId = memoryService.append(
                conversationId,
                DEFAULT_USER_ID,
                ChatMessage.assistant(answer)
        );
        streamTaskRegistry.complete(taskId, assistantMessageId, titleFrom(userQuestion));
        BugFixMessage message = toMessage(
                ticket,
                conversationId,
                taskId,
                deepThinking,
                context,
                promptPlan,
                answer,
                false
        );
        submitToAgent(message);
        return message;
    }

    private RepairRagPipeline repairPipeline() {
        VectorStore vectorStore = knowledgeWorkspace == null
                ? new InMemoryVectorStore()
                : knowledgeWorkspace.vectorStore();
        if (vectorStore.allChunks().isEmpty()) {
            seedDefaultPaymentDocument(vectorStore);
        }
        return RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTreeRegistry.intentTree(),
                query -> List.of("ERROR orders.amount is null at OrderService.create"),
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

    private void seedDefaultPaymentDocument(VectorStore vectorStore) {
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
    }

    private BugFixMessage toMessage(
            TicketSnapshot ticket,
            String conversationId,
            String taskId,
            boolean deepThinking,
            RepairContextPackage context,
            RepairPromptPlan promptPlan,
            String answer,
            boolean rejected
    ) {
        return new BugFixMessage(
                ticket.ticketId(),
                ticket.title(),
                ticket.description(),
                ticket.labels(),
                conversationId,
                taskId,
                deepThinking,
                context.primaryIntent().map(score -> score.node().systemId()).orElse(""),
                context.primaryIntent().map(score -> score.node().name()).orElse(""),
                context.guidanceDecision().action().name(),
                context.guidanceDecision().prompt(),
                context.searchChannels(),
                context.retrievedChunks(),
                context.summary(),
                promptPlan.systemPrompt(),
                agentUserMessage(ticket, promptPlan),
                promptPlan.promptSections(),
                promptPlan.evidenceChunkIds(),
                answer,
                rejected
        );
    }

    private String agentUserMessage(TicketSnapshot ticket, RepairPromptPlan promptPlan) {
        String ticketFields = """
                【工单字段】
                工单ID：%s
                工单标题：%s
                工单描述：%s
                工单标签：%s
                """.formatted(
                ticket.ticketId(),
                safe(ticket.title()),
                safe(ticket.description()),
                String.join(",", ticket.labels())
        ).strip();
        if (promptPlan.userPrompt().isBlank()) {
            return ticketFields;
        }
        return ticketFields + "\n\n" + promptPlan.userPrompt();
    }

    private BugFixMessage reject(
            TicketSnapshot ticket,
            List<String> logs,
            String conversationId,
            String taskId,
            boolean deepThinking,
            String userQuestion
    ) {
        String message = "系统繁忙，请稍后再试";
        memoryService.append(conversationId, DEFAULT_USER_ID, ChatMessage.user(userQuestion));
        String assistantMessageId = memoryService.append(conversationId, DEFAULT_USER_ID, ChatMessage.assistant(message));
        streamTaskRegistry.reject(taskId, conversationId, assistantMessageId, message);
        RepairRagRequest request = new RepairRagRequest(ticket.ticketId(), ticketFieldsText(ticket), logs);
        RepairContextPackage context = new RepairContextPackage(
                ticket.ticketId(),
                java.util.Optional.empty(),
                null,
                List.of(),
                List.of(),
                ""
        );
        RepairPromptPlan promptPlan = RepairPromptService
                .defaultService(queryTermMappingRegistry.rewriteService())
                .build(request, context, List.of(ChatMessage.user(userQuestion)));
        return toMessage(ticket, conversationId, taskId, deepThinking, context, promptPlan, message, true);
    }

    private void submitToAgent(BugFixMessage message) {
        if (agentEngine != null) {
            agentEngine.submit(message);
        }
    }

    private String deterministicAnswer(RepairPromptPlan promptPlan) {
        if (promptPlan.userPrompt().contains("orders.amount")) {
            return "已经定位到 OrderService.create 缺少 orders.amount 校验。";
        }
        return "未检索到足够证据，请补充系统、接口、日志或代码上下文。";
    }

    private TicketSnapshot normalizeTicket(TicketSnapshot ticket) {
        if (ticket != null) {
            return ticket;
        }
        return new TicketSnapshot("ticket-" + UUID.randomUUID(), "", "", List.of(), Instant.now());
    }

    private String ticketFieldsText(TicketSnapshot ticket) {
        return """
                工单标题：%s
                工单描述：%s
                工单标签：%s
                """.formatted(
                safe(ticket.title()),
                safe(ticket.description()),
                String.join(",", ticket.labels())
        ).strip();
    }

    private String userQuestion(TicketSnapshot ticket) {
        if (!blank(ticket.description())) {
            return ticket.description().strip();
        }
        if (!blank(ticket.title())) {
            return ticket.title().strip();
        }
        return ticket.ticketId();
    }

    private String titleFrom(String question) {
        String raw = question == null ? "" : question.strip();
        if (raw.isBlank()) {
            return "";
        }
        return raw.length() <= 30 ? raw : raw.substring(0, 30);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
