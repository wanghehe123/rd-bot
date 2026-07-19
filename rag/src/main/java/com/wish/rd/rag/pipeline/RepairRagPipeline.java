package com.wish.rd.rag.pipeline;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.trace.RagTraceNode;
import com.wish.rd.rag.guidance.model.GuidanceDecision;
import com.wish.rd.rag.guidance.IntentGuidanceService;
import com.wish.rd.rag.intent.IntentClassifier;
import com.wish.rd.rag.intent.model.NodeScore;
import com.wish.rd.rag.retrieval.MultiChannelRetrievalEngine;
import com.wish.rd.rag.retrieval.model.RetrievalBundle;
import com.wish.rd.rag.retrieval.model.RetrievalExecutionResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;
import com.wish.rd.rag.retrieval.observability.RagRetrievalTrace;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
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
 *       当头部意图得分过于接近且项目未绑定知识库时，直接返回提示语而不进入检索；</li>
 *   <li>否则触发 {@link MultiChannelRetrievalEngine} 并行检索并去重排序，打包成上下文；</li>
 *   <li>最后通过 {@link RepairTaskContextPort} 把上下文交给执行层（当前 MVP 为空实现，作为后续交接边界）。</li>
 * </ol>
 */
public final class RepairRagPipeline {

    private final IntentClassifier intentClassifier;
    private final IntentGuidanceService guidanceService;
    private final MultiChannelRetrievalEngine retrievalEngine;
    private final RepairTaskContextPort taskContextPort;
    private final RetrievalRunLifecycle retrievalRunLifecycle;

    /**
     * @param taskContextPort 上下文回调端口，为空时降级为空实现，避免 NPE。
     */
    public RepairRagPipeline(
            IntentClassifier intentClassifier,
            IntentGuidanceService guidanceService,
            MultiChannelRetrievalEngine retrievalEngine,
            RepairTaskContextPort taskContextPort
    ) {
        this(intentClassifier, guidanceService, retrievalEngine, taskContextPort, null);
    }

    /** Optional lifecycle records retrieval state without changing existing RAG callers. */
    public RepairRagPipeline(
            IntentClassifier intentClassifier,
            IntentGuidanceService guidanceService,
            MultiChannelRetrievalEngine retrievalEngine,
            RepairTaskContextPort taskContextPort,
            RetrievalRunLifecycle retrievalRunLifecycle
    ) {
        this.intentClassifier = intentClassifier;
        this.guidanceService = guidanceService;
        this.retrievalEngine = retrievalEngine;
        this.taskContextPort = taskContextPort == null ? context -> { } : taskContextPort;
        this.retrievalRunLifecycle = retrievalRunLifecycle;
    }

    /**
     * 准备修复上下文。这是整个 RAG 主流程的入口方法。
     *
     * <p>标注 {@link RagTraceNode} 表示该方法会被纳入链路追踪记录。
     *
     * @param request 修复请求，含工单 ID、描述与日志
     * @return 打包好的修复上下文；未绑定项目知识库的歧义引导命中时只携带提示语
     */
    @RagTraceNode(value = "repair-rag-pipeline", category = "rag")
    public RepairContextPackage prepareContext(RepairRagRequest request) {
        String query = TextAnalyzer.combined(
                request.rewrittenQuery().isBlank() ? request.description() : request.rewrittenQuery(), request.logs());
        RetrievalRun retrievalRun = startRetrievalRun(request, query);
        RagRetrievalTrace.started(retrievalRun);
        boolean finalized = false;
        try {
            List<NodeScore> rankedIntents = intentClassifier.rank(query);
            Optional<NodeScore> primaryIntent = rankedIntents.stream()
                    .filter(score -> score.score() > 0.0d)
                    .findFirst();

            GuidanceDecision guidanceDecision = guidanceService.decide(rankedIntents);
            String planPreview = "query=" + RagRetrievalTrace.preview(query, 180)
                    + "; primaryIntent=" + primaryIntent.map(score -> score.node().systemId()).orElse("none")
                    + "; guidance=" + guidanceDecision.action().name()
                    + "; knowledgeBaseScope=" + request.projectKnowledgeBaseIds();
            appendArtifact(retrievalRun, "ROUTE_SCOPE", "rag://retrieval/scope",
                    "consumer=BUG_FIX; taskId=" + request.effectiveRetrievalTaskId()
                            + "; knowledgeBaseScope=" + request.projectKnowledgeBaseIds()
                            + "; scopeSource=PROJECT_BINDING");
            appendArtifact(retrievalRun, "RETRIEVAL_PLAN", "rag://retrieval/plan", planPreview);
            RagRetrievalTrace.planned(runId(retrievalRun), planPreview);
            if (guidanceDecision.action() == GuidanceDecision.Action.PROMPT
                    && request.projectKnowledgeBaseIds().isEmpty()) {
                waitForInput(retrievalRun, guidanceDecision.prompt());
                appendArtifact(retrievalRun, "RETRIEVAL_OUTCOME", "rag://retrieval/outcome",
                        "outcome=WAITING_INPUT; reason=" + guidanceDecision.prompt());
                RagRetrievalTrace.outcome(runId(retrievalRun), "WAITING_INPUT", 0, 0, guidanceDecision.prompt());
                finalized = true;
                return new RepairContextPackage(
                        request.ticketId(),
                        primaryIntent,
                        guidanceDecision,
                        List.of(),
                        List.of(),
                        guidanceDecision.prompt()
                );
            }

            RetrievalExecutionResult execution = retrievalEngine.retrieveDetailed(new RetrievalRequest(
                    query,
                    primaryIntent,
                    request.projectKnowledgeBaseIds(),
                    8
            ));
            RetrievalBundle retrievalBundle = execution.bundle();
            recordRetrievalExecution(retrievalRun, execution, retrievalBundle);
            if (!execution.hasSuccessfulChannel()) {
                failRetryable(retrievalRun, "PROVIDER", "全部检索通道失败");
                appendArtifact(retrievalRun, "RETRIEVAL_OUTCOME", "rag://retrieval/outcome",
                        "outcome=FAILED_RETRYABLE; reason=全部检索通道失败");
                RagRetrievalTrace.outcome(runId(retrievalRun), "FAILED_RETRYABLE", 0, 0, "全部检索通道失败");
            } else if (retrievalBundle.chunks().isEmpty()) {
                waitForInput(retrievalRun, "未检索到足够证据，请补充日志、验收条件或项目知识库范围");
                appendArtifact(retrievalRun, "RETRIEVAL_OUTCOME", "rag://retrieval/outcome",
                        "outcome=WAITING_INPUT; reason=未检索到足够证据");
                RagRetrievalTrace.outcome(runId(retrievalRun), "WAITING_INPUT", candidateCount(execution), 0,
                        "未检索到足够证据，请补充日志、验收条件或项目知识库范围");
            } else {
                completeRetrievalRun(retrievalRun, execution, retrievalBundle);
                boolean degraded = execution.channelOutcomes().values().stream().anyMatch(outcome -> outcome.failed());
                String outcome = degraded ? "SUCCEEDED_DEGRADED" : "SUCCEEDED";
                String reason = degraded ? "部分检索通道不可用" : "证据充分";
                appendArtifact(retrievalRun, "RETRIEVAL_OUTCOME", "rag://retrieval/outcome",
                        "outcome=" + outcome + "; candidates=" + candidateCount(execution)
                                + "; selected=" + retrievalBundle.chunks().size() + "; reason=" + reason);
                RagRetrievalTrace.outcome(runId(retrievalRun), outcome, candidateCount(execution),
                        retrievalBundle.chunks().size(), reason);
            }
            finalized = true;
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
        } catch (RuntimeException exception) {
            failRetryable(retrievalRun, exception.getClass().getSimpleName(), exception.getMessage());
            appendArtifact(retrievalRun, "RETRIEVAL_FAILURE", "rag://retrieval/failure",
                    "category=" + exception.getClass().getSimpleName() + "; message=" + exception.getMessage());
            RagRetrievalTrace.failure(runId(retrievalRun), exception.getClass().getSimpleName(), exception.getMessage());
            finalized = true;
            throw exception;
        } finally {
            if (!finalized) {
                failRetryable(retrievalRun, "INTERNAL", "retrieval run left without terminal state");
            }
        }
    }

    private RetrievalRun startRetrievalRun(RepairRagRequest request, String query) {
        if (retrievalRunLifecycle == null) {
            return null;
        }
        return retrievalRunLifecycle.start(
                request.effectiveRetrievalTaskId(), RetrievalConsumerType.BUG_FIX, "", "", query,
                request.projectKnowledgeBaseIds()
        );
    }

    private void completeRetrievalRun(
            RetrievalRun run,
            RetrievalExecutionResult execution,
            RetrievalBundle bundle
    ) {
        if (run == null || retrievalRunLifecycle == null) {
            return;
        }
        boolean degraded = execution.channelOutcomes().values().stream().anyMatch(outcome -> outcome.failed());
        retrievalRunLifecycle.complete(run.runId(),
                execution.channelOutcomes().values().stream().mapToInt(outcome -> outcome.chunkCount()).sum(),
                bundle.chunks().size(), degraded, degraded ? "部分检索通道不可用" : "证据充分");
    }

    private void waitForInput(RetrievalRun run, String reason) {
        if (run != null && retrievalRunLifecycle != null) {
            retrievalRunLifecycle.waitForInput(run.runId(), reason);
        }
    }

    private void failRetryable(RetrievalRun run, String errorCategory, String errorMessage) {
        if (run != null && retrievalRunLifecycle != null) {
            retrievalRunLifecycle.failRetryable(run.runId(), errorCategory, errorMessage);
        }
    }

    private void recordRetrievalExecution(
            RetrievalRun run,
            RetrievalExecutionResult execution,
            RetrievalBundle bundle
    ) {
        if (execution == null) {
            return;
        }
        execution.channelOutcomes().values().forEach(outcome -> {
            String channelName = outcome.channelName().isBlank() ? "unknown" : outcome.channelName();
            appendArtifact(run, "CHANNEL_RESULT", "rag://retrieval/channel/" + channelName,
                    RagRetrievalTrace.channelArtifactPreview(outcome));
            for (int index = 0; index < outcome.result().chunks().size(); index++) {
                RetrievedChunk candidate = outcome.result().chunks().get(index);
                appendArtifact(run, "CHANNEL_CANDIDATE",
                        "rag://retrieval/channel/" + channelName + "/candidate/" + (index + 1),
                        RagRetrievalTrace.candidateArtifactPreview(channelName, index + 1, candidate));
            }
            RagRetrievalTrace.channel(runId(run), outcome);
        });
        if (bundle != null && !bundle.chunks().isEmpty()) {
            appendArtifact(run, "FUSION_RESULT", "rag://retrieval/fusion",
                    "strategy=RRF; k=60; candidates=" + candidateCount(execution)
                            + "; selected=" + bundle.chunks().size()
                            + "; channels=" + bundle.searchChannels());
            for (int index = 0; index < bundle.chunks().size(); index++) {
                RetrievedChunk evidence = bundle.chunks().get(index);
                appendArtifact(run, "SELECTED_EVIDENCE", "rag://retrieval/evidence/" + (index + 1),
                        RagRetrievalTrace.selectedEvidenceArtifactPreview(index + 1, evidence));
            }
            RagRetrievalTrace.selectedEvidence(runId(run), bundle.chunks());
        }
    }

    private void appendArtifact(RetrievalRun run, String artifactType, String artifactUri, String preview) {
        if (run == null || retrievalRunLifecycle == null) {
            return;
        }
        try {
            retrievalRunLifecycle.appendArtifact(
                    run.runId(), artifactType, artifactUri,
                    RagRetrievalTrace.preview(preview, 2_000), RagRetrievalTrace.sha256(preview)
            );
            RagRetrievalTrace.artifact(run.runId(), artifactType, artifactUri, preview);
        } catch (RuntimeException exception) {
            RagRetrievalTrace.failure(run.runId(), "ARTIFACT", exception.getMessage());
        }
    }

    private static int candidateCount(RetrievalExecutionResult execution) {
        return execution == null ? 0 : execution.channelOutcomes().values().stream()
                .mapToInt(outcome -> outcome.chunkCount())
                .sum();
    }

    private static String runId(RetrievalRun run) {
        return run == null ? "" : run.runId();
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
