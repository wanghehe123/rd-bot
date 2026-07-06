package com.wish.rd.rag.pipeline;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.trace.RagTraceNode;
import com.wish.rd.rag.guidance.model.GuidanceDecision;
import com.wish.rd.rag.guidance.IntentGuidanceService;
import com.wish.rd.rag.intent.IntentClassifier;
import com.wish.rd.rag.intent.model.NodeScore;
import com.wish.rd.rag.retrieval.MultiChannelRetrievalEngine;
import com.wish.rd.rag.retrieval.model.RetrievalBundle;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;
import com.wish.rd.rag.text.TextAnalyzer;

import java.util.List;
import java.util.Optional;
import com.wish.rd.rag.pipeline.model.RepairContextPackage;
import com.wish.rd.rag.pipeline.model.RepairRagRequest;

/**
 * 修复场景 RAG 主流程编排器：把"意图分类 → 歧义引导 → 多通道检索 → 上下文打包"串成一条链路。
 *
 * <p>这是连接"用户问题"与"Prompt 规划"的核心枢纽。{@link #prepareContext(RepairRagRequest)}
 * 是唯一的对外入口，返回的 {@link RepairContextPackage} 会直接喂给
 * {@code RepairPromptService} 构建最终 Prompt。
 *
 * <p>流程要点：
 * <ol>
 *   <li>用 {@link TextAnalyzer} 把描述与日志合并成查询文本；</li>
 *   <li>调用 {@link IntentClassifier#rank} 对意图节点评分排序，取首个得分为正者作为主意图；</li>
 *   <li>调用 {@link IntentGuidanceService#decide} 判断是否需要"歧义引导"——
 *       当头部意图得分过于接近时，直接返回提示语而不进入检索；</li>
 *   <li>否则触发 {@link MultiChannelRetrievalEngine} 并行检索并去重排序，打包成上下文；</li>
 *   <li>最后通过 {@link RepairTaskContextPort} 把上下文交给执行层（当前 MVP 为空实现，作为后续交接边界）。</li>
 * </ol>
 */
public final class RepairRagPipeline {

    private final IntentClassifier intentClassifier;
    private final IntentGuidanceService guidanceService;
    private final MultiChannelRetrievalEngine retrievalEngine;
    private final RepairTaskContextPort taskContextPort;

    /**
     * @param taskContextPort 上下文回调端口，为空时降级为空实现，避免 NPE。
     */
    public RepairRagPipeline(
            IntentClassifier intentClassifier,
            IntentGuidanceService guidanceService,
            MultiChannelRetrievalEngine retrievalEngine,
            RepairTaskContextPort taskContextPort
    ) {
        this.intentClassifier = intentClassifier;
        this.guidanceService = guidanceService;
        this.retrievalEngine = retrievalEngine;
        this.taskContextPort = taskContextPort == null ? context -> { } : taskContextPort;
    }

    /**
     * 准备修复上下文。这是整个 RAG 主流程的入口方法。
     *
     * <p>标注 {@link RagTraceNode} 表示该方法会被纳入链路追踪记录。
     *
     * @param request 修复请求，含工单 ID、描述与日志
     * @return 打包好的修复上下文；歧义引导命中时只携带提示语，不携带检索结果
     */
    @RagTraceNode(value = "repair-rag-pipeline", category = "rag")
    public RepairContextPackage prepareContext(RepairRagRequest request) {
        // 1. 合并描述与日志作为检索/分类的统一查询文本
        String query = TextAnalyzer.combined(request.description(), request.logs());
        // 2. 对意图树节点评分并排序
        List<NodeScore> rankedIntents = intentClassifier.rank(query);
        // 3. 取首个得分为正的节点作为主意图（可能为空，表示未命中任何意图）
        Optional<NodeScore> primaryIntent = rankedIntents.stream()
                .filter(score -> score.score() > 0.0d)
                .findFirst();

        // 4. 歧义引导：头部意图得分过于接近时，要求用户补充信息，直接返回提示
        GuidanceDecision guidanceDecision = guidanceService.decide(rankedIntents);
        if (guidanceDecision.action() == GuidanceDecision.Action.PROMPT) {
            return new RepairContextPackage(
                    request.ticketId(),
                    primaryIntent,
                    guidanceDecision,
                    List.of(),
                    List.of(),
                    guidanceDecision.prompt()
            );
        }

        // 5. 触发多通道并行检索（向量/关键词/日志/代码），返回去重排序后的证据块
        RetrievalBundle retrievalBundle = retrievalEngine.retrieve(new RetrievalRequest(query, primaryIntent, 8));
        // 6. 打包为上下文并回调执行层端口（当前 MVP 为空实现）
        RepairContextPackage contextPackage = new RepairContextPackage(
                request.ticketId(),
                primaryIntent,
                guidanceDecision,
                retrievalBundle.chunks(),
                retrievalBundle.searchChannels(),
                summarize(primaryIntent, retrievalBundle.chunks())
        );
        taskContextPort.accept(contextPackage);
        return contextPackage;
    }

    /**
     * 把主意图与检索证据拼成可读摘要，便于人工/日志快速浏览上下文全貌。
     */
    private String summarize(Optional<NodeScore> primaryIntent, List<RetrievedChunk> chunks) {
        StringBuilder summary = new StringBuilder();
        primaryIntent.ifPresent(score -> summary
                .append("目标系统：")
                .append(score.node().name())
                .append('\n'));
        for (RetrievedChunk chunk : chunks) {
            summary.append("[")
                    .append(chunk.knowledgeType())
                    .append("] ")
                    .append(chunk.content())
                    .append('\n');
        }
        return summary.toString().strip();
    }
}
