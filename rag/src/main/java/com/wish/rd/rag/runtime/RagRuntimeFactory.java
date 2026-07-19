package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.CodeRepositorySearchPort;
import com.wish.rd.adapter.LogCenterPort;
import com.wish.rd.rag.guidance.IntentGuidanceService;
import com.wish.rd.rag.ingestion.IngestionPipeline;
import com.wish.rd.rag.ingestion.impl.SimpleIngestionPipeline;
import com.wish.rd.rag.intent.IntentClassifier;
import com.wish.rd.rag.intent.IntentTree;
import com.wish.rd.rag.pipeline.RepairRagPipeline;
import com.wish.rd.rag.pipeline.RepairTaskContextPort;
import com.wish.rd.rag.retrieval.impl.CodeRepositorySearchChannel;
import com.wish.rd.rag.retrieval.impl.GlobalVectorSearchChannel;
import com.wish.rd.rag.retrieval.impl.IntentDirectedVectorSearchChannel;
import com.wish.rd.rag.retrieval.impl.KeywordBM25SearchChannel;
import com.wish.rd.rag.retrieval.impl.LogCenterSearchChannel;
import com.wish.rd.rag.retrieval.MultiChannelRetrievalEngine;
import com.wish.rd.rag.retrieval.SearchChannel;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.vector.VectorStore;

import java.util.List;

/**
 * RAG 运行时工厂：集中装配"摄取管线"与"修复 RAG 主流程"两类核心组件。
 *
 * <p>把散落在多个包里的组件（解析器、分块器、意图分类器、检索引擎、各通道）按典型组合
 * 装配好，避免调用方逐个 new。提供两个 {@code repairRagPipeline} 重载：
 * <ul>
 *   <li>基础版：仅含向量/关键词检索通道；</li>
 *   <li>完整版：额外注入日志中心与代码仓库端口，启用日志/代码检索通道。</li>
 * </ul>
 */
public final class RagRuntimeFactory {

    private RagRuntimeFactory() {
    }

    /**
     * 创建简易摄取管线（基于 {@link SimpleIngestionPipeline}）。
     *
     * @param vectorStore 目标向量库
     */
    public static IngestionPipeline ingestionPipeline(VectorStore vectorStore) {
        return new SimpleIngestionPipeline(vectorStore);
    }

    /**
     * 装配基础版修复 RAG 主流程：意图导向向量检索 + 全局向量检索 + 关键词 BM25 检索。
     *
     * @param vectorStore      共享向量库
     * @param intentTree       意图树（用于分类）
     * @param taskContextPort  上下文回调端口（可空）
     */
    public static RepairRagPipeline repairRagPipeline(
            VectorStore vectorStore,
            IntentTree intentTree,
            RepairTaskContextPort taskContextPort
    ) {
        return repairRagPipeline(vectorStore, intentTree, taskContextPort, null);
    }

    public static RepairRagPipeline repairRagPipeline(
            VectorStore vectorStore,
            IntentTree intentTree,
            RepairTaskContextPort taskContextPort,
            RetrievalRunLifecycle retrievalRunLifecycle
    ) {
        List<SearchChannel> channels = List.of(
                new IntentDirectedVectorSearchChannel(vectorStore),
                new GlobalVectorSearchChannel(vectorStore),
                new KeywordBM25SearchChannel(vectorStore)
        );
        return new RepairRagPipeline(
                new IntentClassifier(intentTree),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(channels),
                taskContextPort,
                retrievalRunLifecycle
        );
    }

    /**
     * 装配完整版修复 RAG 主流程：在基础版基础上额外启用日志中心与代码仓库检索通道。
     *
     * <p>这两个通道依赖外部端口实现，日志/代码端口为 null 时通道自身会在
     * {@code isEnabled} 阶段自动禁用，不会引发异常。
     *
     * @param vectorStore            共享向量库
     * @param intentTree             意图树
     * @param logCenterPort          日志中心端口（可空）
     * @param codeRepositorySearchPort 代码仓库端口（可空）
     * @param taskContextPort        上下文回调端口（可空）
     */
    public static RepairRagPipeline repairRagPipeline(
            VectorStore vectorStore,
            IntentTree intentTree,
            LogCenterPort logCenterPort,
            CodeRepositorySearchPort codeRepositorySearchPort,
            RepairTaskContextPort taskContextPort
    ) {
        return repairRagPipeline(vectorStore, intentTree, logCenterPort, codeRepositorySearchPort, taskContextPort, null);
    }

    public static RepairRagPipeline repairRagPipeline(
            VectorStore vectorStore,
            IntentTree intentTree,
            LogCenterPort logCenterPort,
            CodeRepositorySearchPort codeRepositorySearchPort,
            RepairTaskContextPort taskContextPort,
            RetrievalRunLifecycle retrievalRunLifecycle
    ) {
        List<SearchChannel> channels = List.of(
                new IntentDirectedVectorSearchChannel(vectorStore),
                new GlobalVectorSearchChannel(vectorStore),
                new KeywordBM25SearchChannel(vectorStore),
                new LogCenterSearchChannel(logCenterPort),
                new CodeRepositorySearchChannel(codeRepositorySearchPort)
        );
        return new RepairRagPipeline(
                new IntentClassifier(intentTree),
                new IntentGuidanceService(),
                new MultiChannelRetrievalEngine(channels),
                taskContextPort,
                retrievalRunLifecycle
        );
    }
}
