package com.wish.rd.engine.rag;

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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG V3 聊天总编排引擎：把"对话记忆 → 修复 RAG 主流程 → Prompt 规划 → 确定性回答 → SSE 拼装"
 * 串成一条完整链路，是 {@code /rag/v3/chat} 的业务核心。
 *
 * <p>核心流程（{@link #chat(String, String, boolean)}）：
 * <ol>
 *   <li>生成会话 ID 与任务 ID，并尝试获取全局并发令牌（开启限流时）；</li>
 *   <li>注册任务为 RUNNING，加载历史记忆并把当前用户消息追加进去；</li>
 *   <li>调用 {@link RepairRagPipeline#prepareContext} 完成意图分类、歧义引导与多通道检索，
 *       打包为 {@link RepairContextPackage}；</li>
 *   <li>调用 {@link RepairPromptService#build} 生成系统/用户 Prompt 与各段落；</li>
 *   <li>产出确定性回答（MVP 未接真实 LLM），写入助手消息并标记任务 DONE；</li>
 *   <li>把上述结果拼成 SSE 文本流（meta/delta/done 或限流 reject 事件）。</li>
 * </ol>
 *
 * <p>注意：当前 MVP 的回答是确定性的，仅用于打通控制器与流程契约，尚未接入流式 LLM。
 * 全局限流在进程内基于 CAS 计数实现，上限由 {@code rag.rate-limit.global.max-concurrent} 控制。
 */
public final class RagV3ChatEngine {

    /** MVP 默认测试用户标识（项目当前无用户体系，所有会话挂在该用户下）。 */
    private static final String DEFAULT_USER_ID = "test-user";

    private final ConversationMemoryService memoryService;
    private final QueryTermMappingRegistry queryTermMappingRegistry;
    private final IntentTreeRegistry intentTreeRegistry;
    private final KnowledgeWorkspace knowledgeWorkspace;
    private final RagStreamTaskRegistry streamTaskRegistry;
    /** 全局并发限流开关，对应配置 rag.rate-limit.global.enabled，默认关闭。 */
    private final boolean globalRateLimitEnabled;
    /** 全局最大并发聊天数，对应配置 rag.rate-limit.global.max-concurrent，默认 4。 */
    private final int globalMaxConcurrent;
    /** 当前活跃聊天计数，配合 {@link #tryAcquireChatSlot()} 做 CAS 限流。 */
    private final AtomicInteger activeChats = new AtomicInteger();

    /** 仅用于测试的最小构造器：仅提供记忆服务，其余注册表走默认实现。 */
    public RagV3ChatEngine(ConversationMemoryService memoryService) {
        this(memoryService, QueryTermMappingRegistry.withDefaults(), IntentTreeRegistry.withDefaults(), null);
    }

    /** 测试用构造器：可自定义改写注册表与意图树。 */
    public RagV3ChatEngine(
            ConversationMemoryService memoryService,
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry
    ) {
        this(memoryService, queryTermMappingRegistry, intentTreeRegistry, null);
    }

    /** 测试用构造器：额外注入知识工作区（真实向量库）。 */
    public RagV3ChatEngine(
            ConversationMemoryService memoryService,
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace
    ) {
        this(memoryService, queryTermMappingRegistry, intentTreeRegistry, knowledgeWorkspace, null, false, 4);
    }

    /**
     * 生产用完整构造器，由 {@code RdBotRuntimeConfiguration} 装配。
     * 所有入参允许为空，会回退到默认内存实现，保证引擎可独立测试。
     */
    public RagV3ChatEngine(
            ConversationMemoryService memoryService,
            QueryTermMappingRegistry queryTermMappingRegistry,
            IntentTreeRegistry intentTreeRegistry,
            KnowledgeWorkspace knowledgeWorkspace,
            RagStreamTaskRegistry streamTaskRegistry,
            boolean globalRateLimitEnabled,
            int globalMaxConcurrent
    ) {
        this.memoryService = memoryService == null ? DefaultConversationMemoryService.inMemory() : memoryService;
        this.queryTermMappingRegistry = queryTermMappingRegistry == null
                ? QueryTermMappingRegistry.withDefaults()
                : queryTermMappingRegistry;
        this.intentTreeRegistry = intentTreeRegistry == null ? IntentTreeRegistry.withDefaults() : intentTreeRegistry;
        this.knowledgeWorkspace = knowledgeWorkspace;
        this.streamTaskRegistry = streamTaskRegistry == null ? RagStreamTaskRegistry.inMemory() : streamTaskRegistry;
        this.globalRateLimitEnabled = globalRateLimitEnabled;
        this.globalMaxConcurrent = globalMaxConcurrent;
    }

    /**
     * 聊天主流程：限流 → 记忆加载 → RAG 主流程 → Prompt 规划 → 确定性回答 → SSE 拼装。
     *
     * <p>限流命中时返回 {@code reject} 事件并把用户问题与拒绝消息写入记忆，保持 ragent 响应形态。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID，为空则自动生成
     * @param deepThinking   深度思考开关（当前仅透传）
     * @return 包含 SSE 文本流与结构化字段的流式结果
     */
    public RagV3ChatStreamResult chat(String question, String conversationId, boolean deepThinking) {
        String actualConversationId = blank(conversationId) ? "conversation-" + UUID.randomUUID() : conversationId;
        String taskId = "task-" + UUID.randomUUID();
        String safeQuestion = blank(question) ? "" : question.strip();
        // 开启限流时尝试抢占并发令牌，失败则直接走拒绝分支
        if (!tryAcquireChatSlot()) {
            return reject(actualConversationId, taskId, safeQuestion, deepThinking);
        }

        try {
            streamTaskRegistry.registerRunning(taskId, actualConversationId);
            // 组装本次修复主流程：向量库为空时种入默认支付文档，并注入 mock 的日志/代码端口
            RepairRagPipeline pipeline = repairPipeline();
            // Prompt 规划器使用术语映射注册表提供的改写服务
            RepairPromptService promptService = RepairPromptService.defaultService(queryTermMappingRegistry.rewriteService());
            // 加载历史并把当前用户问题作为最后一轮追加进去
            List<ChatMessage> history = memoryService.loadAndAppend(
                    actualConversationId,
                    DEFAULT_USER_ID,
                    ChatMessage.user(safeQuestion)
            );
            RepairRagRequest request = new RepairRagRequest(
                    taskId,
                    safeQuestion,
                    List.of()
            );
            // RAG 主流程：意图分类 → 歧义引导 → 多通道检索 → 上下文打包
            RepairContextPackage context = pipeline.prepareContext(request);
            // 基于 retrieval 结果与历史记忆构建 Prompt 计划
            RepairPromptPlan promptPlan = promptService.build(request, context, history);
            // MVP 确定性回答：命中 orders.amount 给出定位结论，否则提示证据不足
            String answer = deterministicAnswer(promptPlan);
            // 写入助手消息并完成任务，标题取问题前 30 字
            String assistantMessageId = memoryService.append(actualConversationId, DEFAULT_USER_ID, ChatMessage.assistant(answer));
            streamTaskRegistry.complete(taskId, assistantMessageId, titleFrom(safeQuestion));

            return new RagV3ChatStreamResult(
                    actualConversationId,
                    taskId,
                    deepThinking,
                    promptPlan.promptSections(),
                    answer,
                    toSse(actualConversationId, taskId, answer, promptPlan, context)
            );
        } finally {
            // 无论成功或异常都释放并发令牌，避免限流计数泄漏
            releaseChatSlot();
        }
    }

    /**
     * 停止任务：写入取消态并返回固定停止响应。
     *
     * @param taskId 任务 ID
     */
    public RagV3StopResult stop(String taskId) {
        streamTaskRegistry.cancel(taskId);
        return new RagV3StopResult(taskId, "STOPPED");
    }

    /**
     * 查询任务详情，供 /rag/v3/tasks/{taskId} 调试通道使用。
     *
     * @param taskId 任务 ID
     * @return 任务状态快照
     */
    public RagStreamTask task(String taskId) {
        return streamTaskRegistry.get(taskId);
    }

    /**
     * 组装修复 RAG 主流程管线。
     *
     * <p>向量库为空时种入默认的支付系统文档，保证 MVP 在空库下也能跑通示例。
     * 同时注入两条 mock 的日志/代码端口回调，用于演示 LogCenter 与代码检索通道。
     */
    private RepairRagPipeline repairPipeline() {
        InMemoryVectorStore vectorStore = knowledgeWorkspace == null
                ? new InMemoryVectorStore()
                : knowledgeWorkspace.vectorStore();
        if (vectorStore.allChunks().isEmpty()) {
            seedDefaultPaymentDocument(vectorStore);
        }
        return RagRuntimeFactory.repairRagPipeline(
                vectorStore,
                intentTreeRegistry.intentTree(),
                // mock 日志端口：返回一条金额为空的错误日志
                query -> List.of("ERROR OrderService.create orders.amount is null"),
                // mock 代码端口：返回 OrderService.create 缺少金额校验的代码片段
                query -> List.of(new RetrievedChunk(
                        "payment-service#OrderService.java#42",
                        "OrderService.create should validate amount before repository.save",
                        "payment-system",
                        "code-snippet",
                        "OrderService.java",
                        9.0d,
                        Map.of("repositoryId", query.repositoryId())
                )),
                // 任务上下文回调：当前 MVP 不做额外处理
                context -> {}
        );
    }

    /**
     * 在空向量库中种入默认支付系统文档，确保 /rag/v3/chat 在无前置摄取时也有可检索内容。
     */
    private void seedDefaultPaymentDocument(InMemoryVectorStore vectorStore) {
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

    /**
     * MVP 确定性回答：命中 orders.amount 关键证据给出定位结论，否则提示证据不足。
     * 后续接入真实 LLM 后该方法将被流式生成替代。
     */
    private String deterministicAnswer(RepairPromptPlan promptPlan) {
        if (promptPlan.userPrompt().contains("orders.amount")) {
            return "已经定位到 OrderService.create 缺少 orders.amount 校验。";
        }
        return "未检索到足够证据，请补充系统、接口、日志或代码上下文。";
    }

    /**
     * 把聊天结果拼装成 SSE 文本流。
     *
     * <p>包含三个事件：
     * <ul>
     *   <li>{@code meta}：会话/任务 ID、改写后问题、Prompt 段落、命中意图系统与名称；</li>
     *   <li>{@code delta}：助手回答正文；</li>
     *   <li>{@code done}：固定完成标记。</li>
     * </ul>
     */
    private String toSse(
            String conversationId,
            String taskId,
            String answer,
            RepairPromptPlan promptPlan,
            RepairContextPackage context
    ) {
        return event("meta", """
                {"conversationId":"%s","taskId":"%s","rewrittenQuestion":"%s","promptSections":"%s","intentSystemId":"%s","intentName":"%s"}
                """.formatted(
                        json(conversationId),
                        json(taskId),
                        json(promptPlan.rewrite().rewrittenQuestion()),
                        json(String.join(",", promptPlan.promptSections())),
                        json(context.primaryIntent().map(score -> score.node().systemId()).orElse("")),
                        json(context.primaryIntent().map(score -> score.node().name()).orElse(""))
                ).strip())
                + event("delta", answer)
                + event("done", "{\"status\":\"DONE\"}");
    }

    /**
     * 限流拒绝分支：写回用户问题与拒绝提示，并把任务标记为 REJECTED，
     * 同时拼装 {@code meta}/{@code reject}/{@code finish}/{@code done} 事件流。
     */
    private RagV3ChatStreamResult reject(
            String conversationId,
            String taskId,
            String question,
            boolean deepThinking
    ) {
        String message = "系统繁忙，请稍后再试";
        memoryService.append(conversationId, DEFAULT_USER_ID, ChatMessage.user(question));
        String assistantMessageId = memoryService.append(conversationId, DEFAULT_USER_ID, ChatMessage.assistant(message));
        streamTaskRegistry.reject(taskId, conversationId, assistantMessageId, message);
        String title = titleFrom(question);
        String body = event("meta", """
                {"conversationId":"%s","taskId":"%s"}
                """.formatted(json(conversationId), json(taskId)).strip())
                + event("reject", message)
                + event("finish", """
                {"messageId":"%s","title":"%s"}
                """.formatted(json(assistantMessageId), json(title)).strip())
                + event("done", "{\"status\":\"DONE\"}");
        return new RagV3ChatStreamResult(conversationId, taskId, deepThinking, List.of("限流拒绝"), message, body);
    }

    /**
     * 尝试抢占一个全局并发令牌。关闭限流时直接放行；
     * 用 CAS 自旋保证计数原子性，达到上限即返回 false。
     */
    private boolean tryAcquireChatSlot() {
        if (!globalRateLimitEnabled) {
            return true;
        }
        if (globalMaxConcurrent <= 0) {
            return false;
        }
        while (true) {
            int current = activeChats.get();
            if (current >= globalMaxConcurrent) {
                return false;
            }
            if (activeChats.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 释放并发令牌，使用 Math.max(0, n-1) 防御性兜底，避免计数为负。 */
    private void releaseChatSlot() {
        if (globalRateLimitEnabled && globalMaxConcurrent > 0) {
            activeChats.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    /** 由用户问题生成会话标题，截断到 30 字以内。 */
    private String titleFrom(String question) {
        String raw = question == null ? "" : question.strip();
        if (raw.isBlank()) {
            return "";
        }
        return raw.length() <= 30 ? raw : raw.substring(0, 30);
    }

    /** 简易 JSON 字符串转义：仅处理反斜杠与双引号，满足 SSE meta 字段拼装。 */
    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 拼装单个 SSE 事件块：形如 {@code event: <name>\ndata: <data>\n\n}。 */
    private String event(String event, String data) {
        return "event: " + event + "\n" + "data: " + data + "\n\n";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
