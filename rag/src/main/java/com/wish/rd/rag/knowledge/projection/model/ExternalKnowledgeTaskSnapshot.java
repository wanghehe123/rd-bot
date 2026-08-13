package com.wish.rd.rag.knowledge.projection.model;

/**
 * 远端后台任务的一次观测。
 *
 * @param taskId              远端任务 ID
 * @param state               任务状态
 * @param stage               任务阶段，`completed` 才允许进入版本核验
 * @param semanticErrorCount  语义队列错误数，非 0 不得判定成功
 * @param embeddingErrorCount 向量队列错误数，非 0 不得判定成功
 * @param failureClass        本次查询本身的分类；查询失败时非 {@code NONE}
 * @param errorCode           远端任务或查询的错误码
 * @param errorMessage        已脱敏的错误说明
 */
public record ExternalKnowledgeTaskSnapshot(
        String taskId,
        ExternalKnowledgeTaskState state,
        String stage,
        long semanticErrorCount,
        long embeddingErrorCount,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage
) {

    public ExternalKnowledgeTaskSnapshot {
        taskId = taskId == null ? "" : taskId;
        state = state == null ? ExternalKnowledgeTaskState.UNKNOWN : state;
        stage = stage == null ? "" : stage;
        semanticErrorCount = Math.max(0L, semanticErrorCount);
        embeddingErrorCount = Math.max(0L, embeddingErrorCount);
        failureClass = failureClass == null ? ExternalIndexFailureClass.NONE : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 任务是否已完成且两个队列都没有错误。这是允许进入版本核验的必要条件，
     * 但不是判定 {@code IN_SYNC} 的充分条件。
     *
     * @return 可进入核验时为 true
     */
    public boolean completedCleanly() {
        return state == ExternalKnowledgeTaskState.COMPLETED
                && "completed".equalsIgnoreCase(stage)
                && semanticErrorCount == 0L
                && embeddingErrorCount == 0L;
    }
}
