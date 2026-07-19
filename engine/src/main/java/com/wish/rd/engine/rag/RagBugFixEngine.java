package com.wish.rd.engine.rag;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.DocumentIngestionCommand;
import com.wish.rd.rag.ingestion.DocumentIngestionService;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import com.wish.rd.rag.pipeline.model.RepairContextPackage;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;
import com.wish.rd.rag.prompt.model.RepairPromptPlan;
import com.wish.rd.rag.prompt.RepairPromptService;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.RagRuntimeFactory;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.impl.InMemoryRetrievalRunStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import com.wish.rd.rag.vector.VectorStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.wish.rd.engine.rag.model.BugFixMessage;
import com.wish.rd.engine.rag.model.BugFixStopResult;
import com.wish.rd.engine.rag.model.RagRetrievalLogEvent;

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
    private final RetrievalRunLifecycle retrievalRunLifecycle;

    @Autowired
    public RagBugFixEngine(
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            ObjectProvider<ChatQueueLimiter> chatQueueLimiterProvider,
            ObjectProvider<RagRetrievalLogSink> retrievalLogSinkProvider,
            ObjectProvider<RetrievalRunLifecycle> retrievalRunLifecycleProvider
    ) {
        this(
                queryTermMappingRegistry,
                intentTreeRegistry,
                knowledgeWorkspace,
                streamTaskRegistry,
                chatQueueLimiterProvider.getIfAvailable(ChatQueueLimiter::passThrough),
                retrievalLogSinkProvider.getIfAvailable(RagRetrievalLogSink::noop),
                retrievalRunLifecycleProvider.getIfAvailable(RagBugFixEngine::localRetrievalRunLifecycle)
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
                RagRetrievalLogSink.noop(),
                localRetrievalRunLifecycle()
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
        this(queryTermMappingRegistry, intentTreeRegistry, knowledgeWorkspace, streamTaskRegistry, chatQueueLimiter,
                retrievalLogSink, localRetrievalRunLifecycle());
    }

    public RagBugFixEngine(
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            ChatQueueLimiter chatQueueLimiter,
            RagRetrievalLogSink retrievalLogSink,
            RetrievalRunLifecycle retrievalRunLifecycle
    ) {
        this.queryTermMappingRegistry = queryTermMappingRegistry == null
                ? QueryTermMappingRegistry.withDefaults()
                : queryTermMappingRegistry;
        this.intentTreeRegistry = intentTreeRegistry == null ? IntentTreeRegistry.withDefaults() : intentTreeRegistry;
        this.knowledgeWorkspace = knowledgeWorkspace;
        this.streamTaskRegistry = streamTaskRegistry == null ? RagStreamTaskRegistry.inMemory() : streamTaskRegistry;
        this.chatQueueLimiter = chatQueueLimiter == null ? ChatQueueLimiter.passThrough() : chatQueueLimiter;
        this.retrievalLogSink = retrievalLogSink == null ? RagRetrievalLogSink.noop() : retrievalLogSink;
        this.retrievalRunLifecycle = retrievalRunLifecycle == null ? localRetrievalRunLifecycle() : retrievalRunLifecycle;
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
        return findBugFixMessgaesForAgent(ticket, logs, deepThinking, taskId, List.of());
    }

    /**
     * 使用上游已创建的任务 ID 和项目知识库范围构建 Bug 修复 RAG 消息。
     *
     * @param ticket                  工单快照
     * @param logs                    日志列表
     * @param deepThinking            是否启用深度思考
     * @param taskId                  上游任务 ID
     * @param projectKnowledgeBaseIds 项目绑定的知识库 ID；为空时沿用意图或全局检索
     * @return RAG 上下文消息
     */
    public BugFixMessage findBugFixMessgaesForAgent(
            TicketSnapshot ticket,
            List<String> logs,
            boolean deepThinking,
            String taskId,
            List<String> projectKnowledgeBaseIds
    ) {
        TicketSnapshot safeTicket = normalizeTicket(ticket);
        List<String> safeLogs = logs == null ? List.of() : List.copyOf(logs);
        String safeTaskId = taskId == null || taskId.isBlank()
                ? streamTaskRegistry.createBugFixTask(safeTicket, "P2").taskId()
                : taskId.strip();
        List<String> safeKnowledgeBaseIds = projectKnowledgeBaseIds == null ? List.of() : List.copyOf(projectKnowledgeBaseIds);
        return buildBugFixRagMessage(safeTicket, safeLogs, safeTaskId, deepThinking, safeKnowledgeBaseIds);
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
        BugFixMessage message = buildBugFixRagMessage(ticket, logs, taskId, deepThinking, List.of());
        if (message.retrievedChunks().isEmpty()) {
            streamTaskRegistry.markFailedNeedsHuman(taskId, "RAG 检索证据不足：请补充项目知识库范围、日志或复现条件");
            return message;
        }
        streamTaskRegistry.markExecuting(taskId, message.agentUserMessage());
        streamTaskRegistry.complete(taskId, "", titleFrom(userQuestion));
        return message;
    }

    private BugFixMessage buildBugFixRagMessage(
            TicketSnapshot ticket,
            List<String> logs,
            String taskId,
            boolean deepThinking,
            List<String> projectKnowledgeBaseIds
    ) {
        String ticketText = ticketFieldsText(ticket);
        String rewrittenQuery = queryTermMappingRegistry.rewriteService(projectIdFor(taskId)).rewrite(ticketText);
        RepairRagRequest request = new RepairRagRequest(
                ticket.ticketId(),
                ticketText,
                logs,
                projectKnowledgeBaseIds,
                taskId,
                rewrittenQuery
        );
        RepairContextPackage context = repairPipeline().prepareContext(request);
        appendRetrievalLog(ticket, logs, taskId, deepThinking, context);
        RepairPromptPlan promptPlan = RepairPromptService
                .defaultService(queryTermMappingRegistry.rewriteService(projectIdFor(taskId)))
                .build(request, context);
        String answer = deterministicAnswer(request, context, promptPlan);
        return toMessage(ticket, taskId, deepThinking, context, promptPlan, answer, false);
    }

    /**
     * Rules follow the task's persisted project boundary only. A legacy or unknown task ID has no
     * project scope and therefore receives global rules alone.
     */
    private String projectIdFor(String taskId) {
        try {
            RdTask task = streamTaskRegistry.getTask(taskId);
            if (task instanceof RdBugFixTask bugFixTask) {
                return safe(bugFixTask.projectId()).strip();
            }
            if (task instanceof RdRequirementTask requirementTask) {
                return safe(requirementTask.projectId()).strip();
            }
        } catch (NoSuchElementException ignored) {
            // Compatibility with upstream callers that only have a task ID during migration.
        }
        return "";
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
                        if (isWaimaiPaymentCallbackText(String.join(" ", query.keywords()))) {
                            return List.of("ERROR PaymentCallbackService.handleSuccess completed but orders.status remains PENDING_PAYMENT; expected PAID before merchant notification");
                        }
                        return List.of("ERROR POST /api/orders failed: createOrder payload missing address phone customer_name; client sent delivery_address remark");
                    }
                    return List.of("ERROR orders.amount is null at OrderService.create");
                },
                query -> {
                    if ("waimai".equals(query.repositoryId())) {
                        if (isWaimaiPaymentCallbackText(query.query())) {
                            return List.of(
                                    new RetrievedChunk(
                                            "waimai#server/src/services/PaymentCallbackService.java#handleSuccess",
                                            "server/src/services/PaymentCallbackService.java handleSuccess should mark the order PAID when a successful payment callback is verified.",
                                            "waimai",
                                            "code-snippet",
                                            "server/src/services/PaymentCallbackService.java",
                                            9.6d,
                                            Map.of("repositoryId", query.repositoryId())
                                    ),
                                    new RetrievedChunk(
                                            "waimai#server/src/services/OrderService.java#markPaid",
                                            "server/src/services/OrderService.java markPaid must update orders.status from PENDING_PAYMENT to PAID and persist paidAt/paymentTransactionId.",
                                            "waimai",
                                            "code-snippet",
                                            "server/src/services/OrderService.java",
                                            9.5d,
                                            Map.of("repositoryId", query.repositoryId())
                                    )
                            );
                        }
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
                context -> {},
                retrievalRunLifecycle
        );
    }

    private static RetrievalRunLifecycle localRetrievalRunLifecycle() {
        return new RetrievalRunLifecycle(
                new InMemoryRetrievalRunStore(), () -> UUID.randomUUID().toString(), System::currentTimeMillis
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
        DocumentIngestionService.inMemory(vectorStore).write(new DocumentIngestionCommand(
                "waimai-payment-callback.md",
                "waimai",
                "runbook",
                "text/markdown",
                """
                # 外卖平台支付回调状态修复手册

                故障标题：外卖订单支付成功后状态仍为待支付。
                关键日志：java.lang.IllegalStateException: Payment callback handled but order status remains PENDING_PAYMENT at com.waimai.payment.PaymentCallbackService.handleSuccess(PaymentCallbackService.java:67)
                适用服务：server/src/services/PaymentCallbackService.java、server/src/services/OrderService.java。
                支付成功回调完成后，PaymentCallbackService.handleSuccess 必须在同一事务中把 orders.status 从 PENDING_PAYMENT 更新为 PAID，并写入 paid_at、payment_transaction_id。
                如果只记录 payment transaction 或只确认第三方回调成功，但没有调用 OrderService.markPaid / OrderRepository.updateStatus，订单详情会继续显示待支付，商家无法接单。
                已经是 PAID 的订单应幂等返回成功，不应重复通知商家。
                状态更新成功后再发布 MerchantOrderPaidEvent 或调用商家通知逻辑，通知商家可以接单。
                """.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                160,
                24
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

    private String deterministicAnswer(
            RepairRagRequest request,
            RepairContextPackage context,
            RepairPromptPlan promptPlan
    ) {
        if (!hasRagEvidence(context, promptPlan)) {
            return "未检索到足够证据，请补充系统、接口、日志或代码上下文。";
        }
        String requestText = requestText(request);
        if (requestText.contains("orders.amount")) {
            return "已经定位到 OrderService.create 缺少 orders.amount 校验。";
        }
        if (isWaimaiPaymentCallbackText(requestText)) {
            return "已经定位到 PaymentCallbackService.handleSuccess 支付成功后未把订单状态从 PENDING_PAYMENT 更新为 PAID。";
        }
        return evidenceBasedAnswer(context, promptPlan);
    }

    private String requestText(RepairRagRequest request) {
        if (request == null) {
            return "";
        }
        return request.description() + "\n" + String.join("\n", request.logs());
    }

    private boolean isWaimaiPaymentCallbackText(String text) {
        String raw = safe(text);
        return raw.contains("PaymentCallbackService")
                || raw.contains("PENDING_PAYMENT")
                || raw.contains("PAID")
                || raw.contains("支付回调")
                || raw.contains("支付成功")
                || raw.contains("待支付");
    }

    private boolean hasRagEvidence(RepairContextPackage context, RepairPromptPlan promptPlan) {
        return !context.retrievedChunks().isEmpty() || !promptPlan.evidenceChunkIds().isEmpty();
    }

    private String evidenceBasedAnswer(RepairContextPackage context, RepairPromptPlan promptPlan) {
        List<String> sources = context.retrievedChunks().stream()
                .map(RetrievedChunk::sourceName)
                .filter(source -> source != null && !source.isBlank())
                .distinct()
                .limit(6)
                .toList();
        int evidenceCount = context.retrievedChunks().isEmpty()
                ? promptPlan.evidenceChunkIds().size()
                : context.retrievedChunks().size();
        if (sources.isEmpty()) {
            return "已检索到 %d 个 RAG 证据 chunk，请基于 evidenceChunkIds 继续定位修复。".formatted(evidenceCount);
        }
        return "已检索到 %d 条 RAG 证据，优先查看：%s。".formatted(
                evidenceCount,
                String.join("、", sources)
        );
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
