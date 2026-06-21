package com.wish.rd.rag.core.chunk;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 分块策略工厂：按 {@link ChunkingMode} 维护一组已注册的 {@link ChunkingStrategy}。
 *
 * <p>构造时把传入的策略按其 {@code type()} 装入 EnumMap，运行时按模式查表。
 * {@link #requireStrategy(ChunkingMode)} 在缺失时抛异常，保证管线执行能快速失败。
 */
public final class ChunkingStrategyFactory {

    private final Map<ChunkingMode, ChunkingStrategy> strategies;

    public ChunkingStrategyFactory(List<ChunkingStrategy> strategies) {
        EnumMap<ChunkingMode, ChunkingStrategy> mapped = new EnumMap<>(ChunkingMode.class);
        if (strategies != null) {
            for (ChunkingStrategy strategy : strategies) {
                mapped.put(strategy.type(), strategy);
            }
        }
        this.strategies = Map.copyOf(mapped);
    }

    /**
     * 按模式获取分块策略，mode 为空时默认使用 {@link ChunkingMode#FIXED_SIZE}。
     *
     * @throws IllegalArgumentException 该模式没有注册任何策略时
     */
    public ChunkingStrategy requireStrategy(ChunkingMode mode) {
        ChunkingMode resolvedMode = mode == null ? ChunkingMode.FIXED_SIZE : mode;
        ChunkingStrategy strategy = strategies.get(resolvedMode);
        if (strategy == null) {
            throw new IllegalArgumentException("No chunking strategy configured: " + resolvedMode);
        }
        return strategy;
    }
}
