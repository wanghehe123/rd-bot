package com.wish.rd.rag.core.chunk.strategy.impl;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.core.chunk.model.ChunkingOptions;
import com.wish.rd.rag.core.chunk.ChunkingStrategy;
import com.wish.rd.rag.core.chunk.model.TextBoundaryOptions;
import com.wish.rd.rag.core.chunk.model.VectorChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知分块器：按 Markdown 的标题与段落边界切分，尽量保持语义完整。
 *
 * <p>这是知识库 Markdown 文档默认使用的分块方式，比纯定长切分更友好：
 * <ol>
 *   <li>先按标题行（#{1,6}）与空行把文档切成若干语义块；</li>
 *   <li>累积这些块，达到目标长度（targetChars）就输出一个分块；</li>
 *   <li>下一个块会让当前累积超过上限（maxChars）时，先 flush 当前累积再开始新块；</li>
 *   <li>单个语义块本身就超长时，回退到 {@link FixedSizeTextChunker} 定长切分。</li>
 * </ol>
 *
 * <p>代码块（```）内的内容不参与标题/段落切分，避免破坏代码完整性。
 */
public final class StructureAwareTextChunker implements ChunkingStrategy {

    @Override
    public ChunkingMode type() {
        return ChunkingMode.STRUCTURE_AWARE;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions options) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        TextBoundaryOptions boundary = (TextBoundaryOptions) options;
        // 先把 Markdown 切成语义块（标题/段落/代码块）
        List<String> blocks = splitMarkdownBlocks(text.replace("\r\n", "\n").replace('\r', '\n'));
        ArrayList<VectorChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        for (String block : blocks) {
            // 累积即将超上限时先 flush，保证分块不越界
            if (!current.isEmpty() && current.length() + block.length() > boundary.maxChars()) {
                chunks.add(VectorChunk.of(index++, current.toString().strip()));
                current.setLength(0);
            }
            // 单块本身超长且当前累积为空：回退定长切分
            if (block.length() > boundary.maxChars() && current.isEmpty()) {
                chunks.addAll(splitOversizedBlock(block, boundary, index));
                index = chunks.size();
                continue;
            }
            current.append(block);
            // 达到目标长度即输出，避免分块过大
            if (current.length() >= boundary.targetChars()) {
                chunks.add(VectorChunk.of(index++, current.toString().strip()));
                current.setLength(0);
            }
        }
        // 处理尾部剩余
        if (!current.isEmpty()) {
            chunks.add(VectorChunk.of(index, current.toString().strip()));
        }
        return List.copyOf(chunks);
    }

    /**
     * 把 Markdown 文本按标题与段落切成语义块。
     *
     * <p>规则：遇到标题行且当前已有累积时断块；代码块内不拆分；
     * 空行（非代码块内）也作为段落分隔。
     */
    private List<String> splitMarkdownBlocks(String text) {
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCodeFence = false;
        for (String line : text.split("\n", -1)) {
            String normalizedLine = line + "\n";
            boolean startsHeading = line.matches("^#{1,6}\\s+.*$");
            boolean startsFence = line.startsWith("```");
            // 遇到标题且已有累积：先保存前一块
            if (!inCodeFence && startsHeading && !current.isEmpty()) {
                blocks.add(current.toString());
                current.setLength(0);
            }
            current.append(normalizedLine);
            if (startsFence) {
                inCodeFence = !inCodeFence;
            }
            // 代码块外的空行作为段落边界
            if (!inCodeFence && line.isBlank() && !current.isEmpty()) {
                blocks.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    /**
     * 处理超长单块：委托 {@link FixedSizeTextChunker} 定长切分，并重新分配连续序号。
     */
    private List<VectorChunk> splitOversizedBlock(String block, TextBoundaryOptions boundary, int startIndex) {
        FixedSizeTextChunker fixed = new FixedSizeTextChunker();
        List<VectorChunk> chunks = fixed.chunk(block, boundary.targetChars() <= 0
                ? ChunkingMode.FIXED_SIZE.createDefaultOptions(512, boundary.overlapChars())
                : ChunkingMode.FIXED_SIZE.createDefaultOptions(boundary.targetChars(), boundary.overlapChars()));
        ArrayList<VectorChunk> reindexed = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            reindexed.add(VectorChunk.of(startIndex + i, chunks.get(i).content()));
        }
        return reindexed;
    }
}
