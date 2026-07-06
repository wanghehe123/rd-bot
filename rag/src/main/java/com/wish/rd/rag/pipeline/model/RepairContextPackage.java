package com.wish.rd.rag.pipeline.model;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.guidance.model.GuidanceDecision;
import com.wish.rd.rag.intent.model.NodeScore;

import java.util.List;
import java.util.Optional;

/**
 * 修复上下文打包结果：{@link RepairRagPipeline#prepareContext} 的输出载体。
 *
 * <p>把"主意图 + 歧义引导判定 + 多通道检索证据 + 命中通道列表 + 摘要"聚合成一个不可变记录，
 * 供下游 {@code RepairPromptService} 直接消费。
 *
 * @param ticketId          工单/任务 ID，用于追踪
 * @param primaryIntent     命中的主意图节点评分（可能为空，表示未命中任何意图）
 * @param guidanceDecision  歧义引导判定结果
 * @param retrievedChunks   去重排序后的检索证据块
 * @param searchChannels    实际参与检索的通道名称列表
 * @param summary           人类可读的上下文摘要
 */
public record RepairContextPackage(
        String ticketId,
        Optional<NodeScore> primaryIntent,
        GuidanceDecision guidanceDecision,
        List<RetrievedChunk> retrievedChunks,
        List<String> searchChannels,
        String summary
) {

    /**
     * 紧凑构造器：对所有可空字段做防御性归一，并把集合拷贝为不可变列表，
     * 保证上下文对象在跨线程传递时的安全性。
     */
    public RepairContextPackage {
        primaryIntent = primaryIntent == null ? Optional.empty() : primaryIntent;
        guidanceDecision = guidanceDecision == null ? GuidanceDecision.none() : guidanceDecision;
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        searchChannels = searchChannels == null ? List.of() : List.copyOf(searchChannels);
        summary = summary == null ? "" : summary;
    }
}
