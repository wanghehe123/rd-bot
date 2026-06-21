package com.wish.rd.rag.ingestion;

import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.core.chunk.ChunkingStrategyFactory;
import com.wish.rd.rag.core.chunk.VectorChunk;
import com.wish.rd.rag.core.chunk.strategy.FixedSizeTextChunker;
import com.wish.rd.rag.core.chunk.strategy.StructureAwareTextChunker;
import com.wish.rd.rag.core.parser.DocumentParser;
import com.wish.rd.rag.core.parser.DocumentParserSelector;
import com.wish.rd.rag.core.parser.MarkdownDocumentParser;
import com.wish.rd.rag.core.parser.ParseResult;
import com.wish.rd.rag.core.parser.PlainTextDocumentParser;
import com.wish.rd.rag.vector.VectorStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 摄取任务执行引擎：按管线节点链驱动"获取→解析→分块→索引"四步，产出可检索的分块。
 *
 * <p>这是 /ingestion/tasks 与知识库写文档流程的共同执行器。它把一条由
 * {@link NodeConfig} 组成的有向链跑起来：从起始节点出发，依次执行每个节点对应的
 * FETCHER/PARSER/CHUNKER/INDEXER 动作，并把每步结果与耗时记录到节点日志。
 *
 * <p>执行前会做完整校验：节点不重、后继存在、无环；执行中用计数器防止意外成环导致的死循环。
 * 单个节点失败会记录失败日志并向上抛出，保证失败可追溯。
 */
public final class TaskIngestionEngine {

    private final VectorStore vectorStore;
    /** 解析器选择器：按 MIME 类型选 Markdown 或纯文本解析器。 */
    private final DocumentParserSelector parserSelector;
    /** 分块策略工厂：按 ChunkingMode 选固定长度或结构感知分块器。 */
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    public TaskIngestionEngine(
            VectorStore vectorStore,
            DocumentParserSelector parserSelector,
            ChunkingStrategyFactory chunkingStrategyFactory
    ) {
        this.vectorStore = vectorStore;
        this.parserSelector = parserSelector;
        this.chunkingStrategyFactory = chunkingStrategyFactory;
    }

    /**
     * 内存实现工厂：装配默认的 Markdown/纯文本解析器与两种分块器。
     *
     * @param vectorStore 共享的向量库（写入索引的目标）
     */
    public static TaskIngestionEngine inMemory(VectorStore vectorStore) {
        return new TaskIngestionEngine(
                vectorStore,
                new DocumentParserSelector(List.of(new MarkdownDocumentParser(), new PlainTextDocumentParser())),
                new ChunkingStrategyFactory(List.of(new FixedSizeTextChunker(), new StructureAwareTextChunker()))
        );
    }

    /**
     * 执行一条摄取管线。
     *
     * <p>步骤：校验并构建节点映射 → 找到起始节点 → 沿 next 链逐节点执行 → 返回任务结果。
     *
     * @param pipeline 管线定义
     * @param command  任务命令（来源、类型、分块参数等）
     * @return 摄取结果（原文、分块、节点日志、摘要）
     */
    public IngestionTaskResult execute(PipelineDefinition pipeline, IngestionTaskCommand command) {
        // 校验节点结构：无重复、后继存在、无环
        Map<String, NodeConfig> nodeMap = validateAndMap(pipeline);
        // 起始节点 = 没有被任何节点指向的节点
        String startNodeId = findStartNode(nodeMap);
        if (startNodeId == null) {
            throw new IllegalArgumentException("pipeline cycle or no start node: " + pipeline.id());
        }

        Context context = new Context(command);
        String currentNodeId = startNodeId;
        int executed = 0;
        // 沿 next 链执行，executed 计数防止成环死循环
        while (currentNodeId != null) {
            if (++executed > nodeMap.size()) {
                throw new IllegalArgumentException("pipeline cycle detected: " + pipeline.id());
            }
            NodeConfig config = nodeMap.get(currentNodeId);
            executeNode(config, context);
            currentNodeId = config.nextNodeId();
        }
        return new IngestionTaskResult(
                command.taskId(),
                pipeline.id(),
                IngestionStatus.COMPLETED,
                context.rawText,
                context.retrievedChunks,
                context.logs,
                "已完成 " + context.retrievedChunks.size() + " 个分块"
        );
    }

    /**
     * 执行单个节点：按类型分发到 fetch/parse/chunk/index，记录成功或失败日志。
     */
    private void executeNode(NodeConfig config, Context context) {
        long start = System.currentTimeMillis();
        try {
            String message = switch (config.nodeType()) {
                case FETCHER -> fetch(context);
                case PARSER -> parse(context);
                case CHUNKER -> chunk(context);
                case INDEXER -> index(context);
            };
            context.logs.add(IngestionNodeLog.success(config.nodeType(), message, elapsed(start)));
        } catch (RuntimeException exception) {
            // 失败也记录日志，便于排查具体在哪一步、什么原因出错
            context.logs.add(new IngestionNodeLog(config.nodeType(), exception.getMessage(), elapsed(start), false));
            throw exception;
        }
    }

    /** FETCHER：取出原始字节与 MIME 类型，校验非空。 */
    private String fetch(Context context) {
        context.rawBytes = context.command.content();
        context.mimeType = context.command.mimeType();
        if (context.rawBytes.length == 0) {
            throw new IllegalArgumentException("文档原始字节为空");
        }
        return "已获取 " + context.rawBytes.length + " 字节";
    }

    /** PARSER：按 MIME 选择解析器，把字节解析为纯文本。 */
    private String parse(Context context) {
        if (context.rawBytes.length == 0) {
            throw new IllegalArgumentException("解析器缺少原始字节");
        }
        DocumentParser parser = parserSelector.select(context.mimeType);
        ParseResult parseResult = parser.parse(context.rawBytes, context.mimeType, Map.of());
        context.rawText = parseResult.text();
        return "解析文本长度=" + context.rawText.length();
    }

    /** CHUNKER：按 ChunkingMode 切分文本为多个向量块。 */
    private String chunk(Context context) {
        if (context.rawText == null || context.rawText.isBlank()) {
            throw new IllegalArgumentException("可分块文本为空");
        }
        context.vectorChunks = chunkingStrategyFactory
                .requireStrategy(context.command.chunkingMode())
                .chunk(context.rawText, context.command.chunkingMode().createDefaultOptions(
                        context.command.chunkSize(),
                        context.command.overlapSize()
                ));
        return "已分块 " + context.vectorChunks.size() + " 段";
    }

    /** INDEXER：把分块包装为 RetrievedChunk（含 chunkIndex/taskId 元数据）并写入向量库。 */
    private String index(Context context) {
        if (context.vectorChunks == null || context.vectorChunks.isEmpty()) {
            throw new IllegalArgumentException("没有可索引的分块");
        }
        context.retrievedChunks = context.vectorChunks.stream()
                .map(chunk -> new RetrievedChunk(
                        context.command.sourceName() + "#" + chunk.index(),
                        chunk.content(),
                        context.command.knowledgeBaseId(),
                        context.command.knowledgeType(),
                        context.command.sourceName(),
                        0.0d,
                        Map.of(
                                "chunkIndex", Integer.toString(chunk.index()),
                                "taskId", context.command.taskId()
                        )
                ))
                .toList();
        vectorStore.index(context.retrievedChunks);
        return "已写入 " + context.retrievedChunks.size() + " 个分块";
    }

    /**
     * 校验管线节点：无重复 ID、后继节点存在，并检测环。
     *
     * @return 节点 ID → 节点配置的映射（保持插入顺序）
     */
    private Map<String, NodeConfig> validateAndMap(PipelineDefinition pipeline) {
        if (pipeline.nodes().isEmpty()) {
            throw new IllegalArgumentException("pipeline nodes must not be empty");
        }
        LinkedHashMap<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.nodes()) {
            if (nodeMap.put(node.nodeId(), node) != null) {
                throw new IllegalArgumentException("duplicate pipeline node: " + node.nodeId());
            }
        }
        // 校验每个节点的 next 都指向已存在节点
        for (NodeConfig node : nodeMap.values()) {
            if (node.nextNodeId() != null && !nodeMap.containsKey(node.nextNodeId())) {
                throw new IllegalArgumentException("next node not found: " + node.nextNodeId());
            }
        }
        detectCycles(nodeMap);
        return nodeMap;
    }

    /** 环检测：对每个节点沿 next 走，遇到已访问节点即判定成环。 */
    private void detectCycles(Map<String, NodeConfig> nodeMap) {
        Set<String> fullyVisited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            Set<String> path = new HashSet<>();
            String current = nodeId;
            while (current != null) {
                if (path.contains(current)) {
                    throw new IllegalArgumentException("pipeline cycle detected at node: " + current);
                }
                if (fullyVisited.contains(current)) {
                    break;
                }
                path.add(current);
                fullyVisited.add(current);
                current = nodeMap.get(current).nextNodeId();
            }
        }
    }

    /**
     * 找到起始节点：即没有任何节点的 next 指向它的节点（入度为 0）。
     *
     * @return 起始节点 ID；若所有节点都被指向（成环）则返回 null
     */
    private String findStartNode(Map<String, NodeConfig> nodeMap) {
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (node.nextNodeId() != null) {
                referenced.add(node.nextNodeId());
            }
        }
        return nodeMap.keySet().stream()
                .filter(nodeId -> !referenced.contains(nodeId))
                .findFirst()
                .orElse(null);
    }

    /** 计算耗时（毫秒），下限 0 防止时钟回拨导致的负值。 */
    private long elapsed(long start) {
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    /**
     * 单次管线执行的上下文：在节点间传递中间产物（字节、文本、分块、结果）。
     */
    private static final class Context {

        private final IngestionTaskCommand command;
        /** 节点执行日志，按执行顺序追加。 */
        private final List<IngestionNodeLog> logs = new ArrayList<>();
        private byte[] rawBytes = new byte[0];
        private String mimeType = "text/plain";
        private String rawText = "";
        private List<VectorChunk> vectorChunks = List.of();
        private List<RetrievedChunk> retrievedChunks = List.of();

        private Context(IngestionTaskCommand command) {
            this.command = command;
        }
    }
}
