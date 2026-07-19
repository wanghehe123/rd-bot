package com.wish.rd.rag.retrieval;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.trace.RagTraceNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.wish.rd.rag.retrieval.model.ChannelSearchResult;
import com.wish.rd.rag.retrieval.model.ChannelSearchOutcome;
import com.wish.rd.rag.retrieval.model.RetrievalBundle;
import com.wish.rd.rag.retrieval.model.RetrievalExecutionResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;
import com.wish.rd.rag.retrieval.run.ReciprocalRankFusion;

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
        return retrieveDetailed(request).bundle();
    }

    /**
     * Executes the fan-out without dropping healthy evidence when an optional source is unavailable.
     * Callers can inspect every channel outcome and let their retrieval policy decide whether the
     * partial result is sufficient, degraded, or retryable.
     */
    public RetrievalExecutionResult retrieveDetailed(RetrievalRequest request) {
        // 1. 按请求上下文动态筛选启用的通道（意图命中与否决定不同通道组合）
        List<SearchChannel> enabledChannels = channels.stream()
                .filter(channel -> channel.isEnabled(request))
                .toList();
        // 2. 每个通道分配一个虚拟线程并行检索
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ChannelSearchOutcome>> futures = enabledChannels.stream()
                    .map(channel -> CompletableFuture.supplyAsync(() -> search(channel, request), executor))
                    .toList();
            // 3. 等待全部通道返回；单通道异常被收敛为 outcome，而不是中断整轮取证。
            List<ChannelSearchOutcome> outcomes = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            List<ChannelSearchResult> successfulResults = outcomes.stream()
                    .filter(outcome -> !outcome.failed())
                    .map(ChannelSearchOutcome::result)
                    .toList();
            Map<String, ChannelSearchOutcome> outcomeMap = new LinkedHashMap<>();
            outcomes.forEach(outcome -> outcomeMap.put(outcome.channelName(), outcome));
            return new RetrievalExecutionResult(merge(successfulResults, request.topK()), outcomeMap);
        }
    }

    private ChannelSearchOutcome search(SearchChannel channel, RetrievalRequest request) {
        long startedAt = System.nanoTime();
        try {
            return new ChannelSearchOutcome(
                    channel.name(), channel.search(request), false, "", "", elapsedMillis(startedAt)
            );
        } catch (RuntimeException exception) {
            return new ChannelSearchOutcome(
                    channel.name(), null, true, exception.getClass().getSimpleName(),
                    exception.getMessage(), elapsedMillis(startedAt)
            );
        }
    }

    /**
     * 合并各通道结果：按 chunkId 去重（保留高分），再按分数降序排序。
     */
    private RetrievalBundle merge(List<ChannelSearchResult> channelResults, int topK) {
        int limit = Math.max(1, topK <= 0
                ? channelResults.stream().mapToInt(result -> result.chunks().size()).sum()
                : topK);
        List<RetrievedChunk> chunks = ReciprocalRankFusion.fuse(channelResults, 60, limit);
        List<String> channelNames = channelResults.stream()
                .map(ChannelSearchResult::channelName)
                .toList();
        return new RetrievalBundle(channelNames, chunks);
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
