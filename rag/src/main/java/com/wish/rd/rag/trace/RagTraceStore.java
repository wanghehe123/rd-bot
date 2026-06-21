package com.wish.rd.rag.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * RAG 链路追踪存储：记录 run（一次完整链路）与 node（链路中的步骤）两级轨迹。
 *
 * <p>供 /rag/traces 系列接口查询。run 记录入口方法、会话/任务关联、状态、耗时与额外数据；
 * node 记录每个标注 {@link com.wish.rd.framework.trace.RagTraceNode} 的方法调用。
 *
 * <p>典型用法：测试通道在跑 prompt-flow 等链路前 {@link #startRun}，每个关键方法
 * 调用 {@link #recordNode}，结束时 {@link #finishRun}。所有方法 synchronized 保证并发安全。
 */
@Component
public final class RagTraceStore {

    /** traceId → 可变 run（状态/耗时会在结束时更新）。 */
    private final LinkedHashMap<String, MutableRun> runs = new LinkedHashMap<>();
    /** traceId → 该链路的节点视图列表。 */
    private final LinkedHashMap<String, List<RagTraceNodeView>> nodesByTraceId = new LinkedHashMap<>();

    /** 开始一次链路追踪：创建 RUNNING 状态的 run，并初始化节点列表。 */
    public synchronized void startRun(RagTraceRunStart start) {
        long now = System.currentTimeMillis();
        runs.put(start.traceId(), new MutableRun(
                start.traceId(),
                start.traceName(),
                start.entryMethod(),
                start.conversationId(),
                start.taskId(),
                start.userId(),
                "RUNNING",
                null,
                now,
                0L,
                0L,
                start.extraData()
        ));
        nodesByTraceId.put(start.traceId(), new ArrayList<>());
    }

    /** 结束链路：更新状态、错误信息与总耗时。status 为空时默认 SUCCESS。 */
    public synchronized void finishRun(String traceId, String status, String errorMessage, long durationMs) {
        MutableRun run = requireRun(traceId);
        run.status = status == null || status.isBlank() ? "SUCCESS" : status;
        run.errorMessage = errorMessage;
        run.durationMs = Math.max(0L, durationMs);
        run.endTimeEpochMillis = System.currentTimeMillis();
    }

    /**
     * 记录一个节点步骤。要求对应 run 已 startRun，否则抛异常。
     * 根据耗时反推起始时间，便于在没有显式 start 时序时重建时间线。
     */
    public synchronized void recordNode(RagTraceNodeRecord record) {
        requireRun(record.traceId());
        long end = System.currentTimeMillis();
        long durationMs = Math.max(0L, record.durationMs());
        long start = Math.max(0L, end - durationMs);
        nodesByTraceId.computeIfAbsent(record.traceId(), ignored -> new ArrayList<>())
                .add(new RagTraceNodeView(
                        record.traceId(),
                        record.nodeId(),
                        record.parentNodeId(),
                        record.depth(),
                        record.nodeType(),
                        record.nodeName(),
                        record.className(),
                        record.methodName(),
                        record.status(),
                        record.errorMessage(),
                        durationMs,
                        start,
                        end,
                        record.extraData()
                ));
    }

    /** 列出全部 run，按开始时间降序（最新的在前）。 */
    public synchronized List<RagTraceRunView> listRuns() {
        return runs.values().stream()
                .map(MutableRun::toView)
                .sorted(Comparator.comparingLong(RagTraceRunView::startTimeEpochMillis).reversed())
                .toList();
    }

    /** 查询单个 run 的详情（含其全部节点）。 */
    public synchronized RagTraceDetail detail(String traceId) {
        return new RagTraceDetail(requireRun(traceId).toView(), listNodes(traceId));
    }

    /** 列出某链路的全部节点视图。 */
    public synchronized List<RagTraceNodeView> listNodes(String traceId) {
        requireRun(traceId);
        return List.copyOf(nodesByTraceId.getOrDefault(traceId, List.of()));
    }

    /** 清空全部追踪数据。 */
    public synchronized void clear() {
        runs.clear();
        nodesByTraceId.clear();
    }

    /** 校验 run 存在，不存在抛异常。 */
    private MutableRun requireRun(String traceId) {
        MutableRun run = runs.get(traceId);
        if (run == null) {
            throw new IllegalArgumentException("trace run not found: " + traceId);
        }
        return run;
    }

    /**
     * 可变的 run 内部表示：状态、错误、耗时、结束时间在运行过程中会被更新，
     * 其余字段不可变。对外暴露时通过 {@link #toView()} 转为不可变视图。
     */
    private static final class MutableRun {
        private final String traceId;
        private final String traceName;
        private final String entryMethod;
        private final String conversationId;
        private final String taskId;
        private final String userId;
        private final long startTimeEpochMillis;
        private final Map<String, String> extraData;
        private String status;
        private String errorMessage;
        private long endTimeEpochMillis;
        private long durationMs;

        private MutableRun(
                String traceId,
                String traceName,
                String entryMethod,
                String conversationId,
                String taskId,
                String userId,
                String status,
                String errorMessage,
                long startTimeEpochMillis,
                long endTimeEpochMillis,
                long durationMs,
                Map<String, String> extraData
        ) {
            this.traceId = traceId;
            this.traceName = traceName;
            this.entryMethod = entryMethod;
            this.conversationId = conversationId;
            this.taskId = taskId;
            this.userId = userId;
            this.status = status;
            this.errorMessage = errorMessage;
            this.startTimeEpochMillis = startTimeEpochMillis;
            this.endTimeEpochMillis = endTimeEpochMillis;
            this.durationMs = durationMs;
            this.extraData = extraData == null ? Map.of() : Map.copyOf(extraData);
        }

        /** 转为对外不可变视图，question 从 extraData 中提取作为便捷字段。 */
        private RagTraceRunView toView() {
            return new RagTraceRunView(
                    traceId,
                    traceName,
                    entryMethod,
                    conversationId,
                    taskId,
                    userId,
                    status,
                    errorMessage,
                    durationMs,
                    startTimeEpochMillis,
                    endTimeEpochMillis,
                    extraData.getOrDefault("question", ""),
                    extraData
            );
        }
    }
}
