package com.wish.rd.rag.core.chunk;

import java.util.List;
import com.wish.rd.rag.core.chunk.model.ChunkingOptions;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.core.chunk.model.VectorChunk;

/**
 * 分块策略接口：把纯文本切分为多个 {@link VectorChunk}，供向量库索引。
 *
 * <p>由 {@link com.wish.rd.rag.core.chunk.ChunkingStrategyFactory} 按模式管理，
 * 当前有两种实现：定长切分（{@link com.wish.rd.rag.core.chunk.strategy.impl.FixedSizeTextChunker}）
 * 与结构感知切分（{@link com.wish.rd.rag.core.chunk.strategy.impl.StructureAwareTextChunker}）。
 */
public interface ChunkingStrategy {

    /** 该策略对应的分块模式。 */
    ChunkingMode type();

    /**
     * 把文本切分为带序号的向量块。
     *
     * @param text    已解析的纯文本
     * @param options 分块参数（定长/边界等）
     * @return 向量块列表
     */
    List<VectorChunk> chunk(String text, ChunkingOptions options);
}
