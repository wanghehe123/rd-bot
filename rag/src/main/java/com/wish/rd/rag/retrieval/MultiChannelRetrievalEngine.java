package com.wish.rd.rag.retrieval;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.trace.RagTraceNode;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.RetrievalBundle;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;

/**
 * 多通道检索引擎：用 Java 虚拟线程并行调度所有启用的检索通道，并负责合并去重。
 *
 * <p>检索通道（{@link SearchChannel}）按"是否启用"动态筛选：
 * <ul>
 *   <li>意图命中时启用：意图导向向量检索、日志中心检索、代码仓库检索；</li>
 *   <li>意图缺失时启用：全局向量检索；</li>
 *   <li>始终启用：关键词 BM25 检索。</li>
 * </ul>
 *
 * <p>并行执行借助 {@code newVirtualThreadPerTaskExecutor()}，每个通道一个虚拟线程，
 * 通过 {@link CompletableFuture} 收集结果后 {@code join} 等待全部完成。
 * 合并阶段以 {@code chunkId} 为键去重，相同块取分数较高者，最终按分数降序排列。
 */
public final class MultiChannelRetrievalEngine {

    private final List<SearchChannel> channels;

    /**
     * @param channels 注入的检索通道列表，为空或 null 时视为无可用通道
     */
    public MultiChannelRetrievalEngine(List<SearchChannel> channels) {
        this.channels = channels == null ? List.of() : List.copyOf(channels);
    }

    /**
     * 执行多通道并行检索并合并结果。
     *
     * <p>标注 {@link RagTraceNode} 表示该方法会被纳入链路追踪。
     *
     * @param request 检索请求（含查询文本、主意图、topK）
     * @return 合并去重后的检索结果包
     */
    @RagTraceNode(value = "multi-channel-retrieval", category = "rag")
    public RetrievalBundle retrieve(RetrievalRequest request) {
        // 1. 按请求上下文动态筛选启用的通道（意图命中与否决定不同通道组合）
        List<SearchChannel> enabledChannels = channels.stream()
                .filter(channel -> channel.isEnabled(request))
                .toList();
        // 2. 每个通道分配一个虚拟线程并行检索
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ChannelSearchResult>> futures = enabledChannels.stream()
                    .map(channel -> CompletableFuture.supplyAsync(() -> channel.search(request), executor))
                    .toList();
            // 3. 等待全部通道返回（任一异常会在此抛出）
            List<ChannelSearchResult> channelResults = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            return merge(channelResults);
        }
    }

    /**
     * 合并各通道结果：按 chunkId 去重（保留高分），再按分数降序排序。
     */
    private RetrievalBundle merge(List<ChannelSearchResult> channelResults) {
        // LinkedHashMap 保持插入顺序，便于稳定输出
        Map<String, RetrievedChunk> deduplicated = new LinkedHashMap<>();
        for (ChannelSearchResult channelResult : channelResults) {
            for (RetrievedChunk chunk : channelResult.chunks()) {
                deduplicated.merge(
                        chunk.chunkId(),
                        chunk,
                        // 同一 chunk 被多通道命中时，保留得分更高的那个
                        (left, right) -> left.score() >= right.score() ? left : right
                );
            }
        }
        List<RetrievedChunk> chunks = deduplicated.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .toList();
        List<String> channelNames = channelResults.stream()
                .map(ChannelSearchResult::channelName)
                .toList();
        return new RetrievalBundle(channelNames, chunks);
    }
}
