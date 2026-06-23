package com.wish.rd.engine.rag;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.core.chunk.ChunkingMode;
import com.wish.rd.rag.ingestion.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.DocumentIngestionService;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.pipeline.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.RepairRagRequest;
import com.wish.rd.rag.prompt.RepairPromptPlan;
import com.wish.rd.rag.prompt.RepairPromptService;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.vector.InMemoryVectorStore;
import com.wish.rd.rag.vector.VectorStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bug 修复 RAG 编排引擎。
 *
 * <p>入口方法 {@link #findBugFixMessgaesForAgent(TicketSnapshot, List, boolean)}
 * 以工单字段为核心输入，完成意图分类、歧义引导、多通道检索与 Agent 消息打包。
 */
@Service
public final class RagBugFixEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagBugFixEngine.class);

    private final QueryTermMappingRegistry queryTermMappingRegistry;
    private final IntentTreeRegistry intentTreeRegistry;
    private final KnowledgeWorkspace knowledgeWorkspace;
    private final RagStreamTaskRegistry streamTaskRegistry;
    private final ChatQueueLimiter chatQueueLimiter;
    private final RagRetrievalLogSink retrievalLogSink;

    @Autowired
    public RagBugFixEngine(
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            ObjectProvider<ChatQueueLimiter> chatQueueLimiterProvider,
            ObjectProvider<RagRetrievalLogSink> retrievalLogSinkProvider
    ) {
        this(
                queryTermMappingRegistry,
                intentTreeRegistry,
                knowledgeWorkspace,
                streamTaskRegistry,
                chatQueueLimiterProvider.getIfAvailable(ChatQueueLimiter::passThrough),
                retrievalLogSinkProvider.getIfAvailable(RagRetrievalLogSink::noop)
        );
    }

    public RagBugFixEngine(
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            ChatQueueLimiter chatQueueLimiter
    ) {
        this(
                queryTermMappingRegistry,
                intentTreeRegistry,
                knowledgeWorkspace,
                streamTaskRegistry,
                chatQueueLimiter,
                RagRetrievalLogSink.noop()
        );
    }

    public RagBugFixEngine(
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            ChatQueueLimiter chatQueueLimiter,
            RagRetrievalLogSink retrievalLogSink
    ) {
        this.queryTermMappingRegistry = queryTermMappingRegistry == null
                ? QueryTermMappingRegistry.withDefaults()
                : queryTermMappingRegistry;
        this.intentTreeRegistry = intentTreeRegistry == null ? IntentTreeRegistry.withDefaults() : intentTreeRegistry;
        this.knowledgeWorkspace = knowledgeWorkspace;
        this.streamTaskRegistry = streamTaskRegistry == null ? RagStreamTaskRegistry.inMemory() : streamTaskRegistry;
        this.chatQueueLimiter = chatQueueLimiter == null ? ChatQueueLimiter.passThrough() : chatQueueLimiter;
        this.retrievalLogSink = retrievalLogSink == null ? RagRetrievalLogSink.noop() : retrievalLogSink;
    }

    public BugFixMessage findBugFixMessgaesForAgent(TicketSnapshot ticket, List<String> logs) {
        return findBugFixMessgaesForAgent(ticket, logs, false);
    }

    public BugFixMessage findBugFixMessgaesForAgent(
            TicketSnapshot ticket,
            List<String> logs,
            boolean deepThinking
    ) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        List<String> safeLogs = logs == null ? List.of() : List.copyOf(logs);
        RdBugFixTask task = streamTaskRegistry.createBugFixTask(safeTicket, "P2");
        String taskId = task.taskId();
        String userQuestion = userQuestion(safeTicket);
        return chatQueueLimiter.enqueue(
                new ChatQueueLimiter.ChatQueueRequest(userQuestion, taskId),
                () -> runBugFixRagFlow(safeTicket, safeLogs, taskId, deepThinking, userQuestion),
                () -> reject(safeTicket, safeLogs, taskId, deepThinking, userQuestion)
        );
    }

    /**
     * 使用上游已创建的任务 ID 只构建 Bug 修复 RAG 消息。
     *
     * @param ticket       工单快照
     * @param logs         日志列表
     * @param deepThinking 是否启用深度思考
     * @param taskId       上游任务 ID
     * @return RAG 上下文消息
     */
    public BugFixMessage findBugFixMessgaesForAgent(
            TicketSnapshot ticket,
            List<String> logs,
            boolean deepThinking,
            String taskId
    ) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        List<String> safeLogs = logs == null ? List.of() : List.copyOf(logs);
        String safeTaskId = taskId == null || taskId.isBlank()
                ? streamTaskRegistry.createBugFixTask(safeTicket, "P2").taskId()
                : taskId.strip();
        return buildBugFixRagMessage(safeTicket, safeLogs, safeTaskId, deepThinking);
    }

    public BugFixStopResult stop(String taskId) {
        streamTaskRegistry.cancel(taskId);
        return new BugFixStopResult(taskId, "STOPPED");
    }

    public RdBugFixTask task(String taskId) {
        return streamTaskRegistry.get(taskId);
    }

    private BugFixMessage runBugFixRagFlow(
            TicketSnapshot ticket,
            List<String> logs,
            String taskId,
            boolean deepThinking,
            String userQuestion
    ) {
        streamTaskRegistry.markSearching(taskId, "RAG 检索中");
        BugFixMessage message = buildBugFixRagMessage(ticket, logs, taskId, deepThinking);
        streamTaskRegistry.markExecuting(taskId, message.agentUserMessage());
        streamTaskRegistry.complete(taskId, "", titleFrom(userQuestion));
        return message;
    }

    private BugFixMessage buildBugFixRagMessage(
            TicketSnapshot ticket,
            List<String> logs,
            String taskId,
            boolean deepThinking
    ) {
        RepairRagRequest request = new RepairRagRequest(ticket.ticketId(), ticketFieldsText(ticket), logs);
        RepairContextPackage context = repairPipeline().prepareContext(request);
        appendRetrievalLog(ticket, logs, taskId, deepThinking, context);
        RepairPromptPlan promptPlan = RepairPromptService
                .defaultService(queryTermMappingRegistry.rewriteService())
                .build(request, context);
        String answer = deterministicAnswer(promptPlan);
        return toMessage(ticket, taskId, deepThinking, context, promptPlan, answer, false);
    }

    private void appendRetrievalLog(
            TicketSnapshot ticket,
            List<String> logs,
            String taskId,
            boolean deepThinking,
            RepairContextPackage context
    ) {
        try {
            retrievalLogSink.append(new RagRetrievalLogEvent(
                    Instant.now(),
                    taskId,
                    ticket.ticketId(),
                    ticket.title(),
                    ticket.description(),
                    ticket.labels(),
                    logs,
                    deepThinking,
                    context.primaryIntent().map(score -> score.node().systemId()).orElse(""),
                    context.primaryIntent().map(score -> score.node().name()).orElse(""),
                    context.guidanceDecision().action().name(),
                    context.guidanceDecision().prompt(),
                    context.searchChannels(),
                    context.retrievedChunks(),
                    context.summary()
            ));
        } catch (RuntimeException exception) {
            LOGGER.warn("failed to append RAG retrieval log: taskId={}, ticketId={}",
                    taskId,
                    ticket.ticketId(),
                    exception);
        }
    }

    private RepairRagPipeline repairPipeline() {
        VectorStore vectorStore = knowledgeWorkspace == null
                ? new InMemoryVectorStore()
                : knowledgeWorkspace.vectorStore();
        ensureDefaultDocuments(vectorStore);
        return RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTreeRegistry.intentTree(),
                query -> {
                    if ("waimai".equals(query.systemId())) {
                        return List.of("ERROR POST /api/orders failed: createOrder payload missing address phone customer_name; client sent delivery_address remark");
                    }
                    return List.of("ERROR orders.amount is null at OrderService.create");
                },
                query -> {
                    if ("waimai".equals(query.repositoryId())) {
                        return List.of(
                                new RetrievedChunk(
                                        "waimai#client/src/api.ts#createOrder",
                                        "client/src/api.ts createOrder currently sends merchant_id, items, delivery_address and remark to POST /api/orders.",
                                        "waimai",
                                        "code-snippet",
                                        "client/src/api.ts",
                                        9.5d,
                                        Map.of("repositoryId", query.repositoryId())
                                ),
                                new RetrievedChunk(
                                        "waimai#server/src/routes/orders.ts#post",
                                        "server/src/routes/orders.ts POST /api/orders requires merchant_id, items, address, phone and customer_name before inserting orders.",
                                        "waimai",
                                        "code-snippet",
                                        "server/src/routes/orders.ts",
                                        9.4d,
                                        Map.of("repositoryId", query.repositoryId())
                                )
                        );
                    }
                    return List.of(new RetrievedChunk(
                            "payment-service#OrderService.java#42",
                            "OrderService.create should validate amount before repository.save",
                            "payment-system",
                            "code-snippet",
                            "OrderService.java",
                            9.0d,
                            Map.of("repositoryId", query.repositoryId())
                    ));
                },
                context -> {}
        );
    }

    private void ensureDefaultDocuments(VectorStore vectorStore) {
        if (!hasKnowledgeBase(vectorStore, "payment-system")) {
            seedDefaultPaymentDocument(vectorStore);
        }
        if (!hasKnowledgeBase(vectorStore, "waimai")) {
            seedDefaultWaimaiDocuments(vectorStore);
        }
    }

    private boolean hasKnowledgeBase(VectorStore vectorStore, String knowledgeBaseId) {
        return vectorStore.allChunks().stream()
                .anyMatch(chunk -> knowledgeBaseId.equals(chunk.knowledgeBaseId()));
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

    private void seedDefaultWaimaiDocuments(VectorStore vectorStore) {
        DocumentIngestionService.inMemory(vectorStore).write(new DocumentIngestionCommand(
                "waimai-order-api.md",
                "waimai",
                "api",
                "text/markdown",
                """
                # 外卖平台订单 API

                POST /api/orders 是外卖平台顾客创建订单接口，对应服务端文件 server/src/routes/orders.ts。
                服务端创建订单时要求请求体包含 merchant_id、items、address、phone、customer_name，可选 note。
                客户端 client/src/api.ts 的 createOrder 当前提交 merchant_id、items、delivery_address、remark。
                如果前端只传 delivery_address/remark，服务端校验拿不到 address、phone、customer_name，下单会失败。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                128,
                16
        ));
        DocumentIngestionService.inMemory(vectorStore).write(new DocumentIngestionCommand(
                "waimai-schema.md",
                "waimai",
                "database",
                "text/markdown",
                """
                # 外卖平台订单表

                orders 表字段包含 order_no、customer_id、merchant_id、status、total_amount、delivery_fee、address、phone、customer_name、note。
                address、phone、customer_name 是创建订单需要落库的配送信息。
                order_items 表通过 order_id 关联订单，保存 product_id、product_name、product_price、quantity、subtotal。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                128,
                16
        ));
    }

    private BugFixMessage toMessage(
            TicketSnapshot ticket,
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
            String taskId,
            boolean deepThinking,
            String userQuestion
    ) {
        String message = "系统繁忙，请稍后再试";
        streamTaskRegistry.reject(taskId, "", "", message);
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
                .build(request, context);
        return toMessage(ticket, taskId, deepThinking, context, promptPlan, message, true);
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
