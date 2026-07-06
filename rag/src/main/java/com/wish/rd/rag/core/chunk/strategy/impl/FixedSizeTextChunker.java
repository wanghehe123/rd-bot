package com.wish.rd.rag.core.chunk.strategy.impl;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.core.chunk.model.ChunkingOptions;
import com.wish.rd.rag.core.chunk.ChunkingStrategy;
import com.wish.rd.rag.core.chunk.model.FixedSizeOptions;
import com.wish.rd.rag.core.chunk.model.VectorChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 定长分块器：按固定字符数切分文本，支持重叠与边界对齐。
 *
 * <p>切分策略：
 * <ol>
 *   <li>把软换行（\r\n、\r）统一为 \n；</li>
 *   <li>以 chunkSize 为目标长度切分，相邻块保留 overlap 个字符的重叠，避免语义被截断；</li>
 *   <li>切分点尽量对齐到换行或中英文标点（。！？;;）边界，提升可读性。</li>
 * </ol>
 */
public final class FixedSizeTextChunker implements ChunkingStrategy {

    @Override
    public ChunkingMode type() {
        return ChunkingMode.FIXED_SIZE;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions options) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        FixedSizeOptions fixed = (FixedSizeOptions) options;
        int chunkSize = fixed.chunkSize();
        int overlap = fixed.overlapSize();
        String normalized = normalizeSoftBreaks(text);
        ArrayList<VectorChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < normalized.length()) {
            int targetEnd = Math.min(start + chunkSize, normalized.length());
            // 尝试把切分点对齐到附近的句子/段落边界
            int end = alignBoundary(normalized, start, targetEnd, overlap);
            // 对齐失败时回退到目标长度
            if (end <= start) {
                end = targetEnd;
            }
            String content = normalized.substring(start, end).strip();
            if (!content.isBlank()) {
                chunks.add(VectorChunk.of(index++, content));
            }
            if (end >= normalized.length()) {
                break;
            }
            // 下一块起点：保留 overlap 重叠，避免边界处语义割裂
            start = Math.max(end - overlap, end == start ? end : start + 1);
        }
        return List.copyOf(chunks);
    }

    /**
     * 在 [targetEnd-overlap, targetEnd] 范围内寻找最近的句子边界（换行/中英文标点），
     * 让切分点落在语义自然处。找不到合适边界则返回原始 targetEnd。
     */
    private int alignBoundary(String text, int start, int targetEnd, int overlap) {
        int maxLookback = Math.min(overlap, targetEnd - start);
        for (int offset = 0; offset <= maxLookback; offset++) {
            int pos = targetEnd - offset - 1;
            if (pos <= start) {
                break;
            }
            char c = text.charAt(pos);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == ';' || c == '；') {
                return pos + 1;
            }
        }
        return targetEnd;
    }

    /** 统一软换行：\r\n 和 \r 都规整为 \n。 */
    private String normalizeSoftBreaks(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
