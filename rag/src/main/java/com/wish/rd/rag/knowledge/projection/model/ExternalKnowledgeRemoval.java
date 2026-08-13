package com.wish.rd.rag.knowledge.projection.model;

/**
 * 一次远端删除的受理结果。HTTP 2xx 且拿到信封只代表远端接受了删除，
 * 资源是否真的缺席要靠后续 {@code inspectResource} 确认。
 *
 * <p>{@code requestIssued} 决定上层是退避重试还是查询收敛：没写出字节可以再发，
 * 已经交给连接的删除禁止重放。
 *
 * @param removed        远端接受了这次删除（含幂等的 count=0）
 * @param deletedCount   {@code result.estimated_deleted_count}
 * @param failureClass   结果分类，成功时为 {@link ExternalIndexFailureClass#NONE}
 * @param errorCode      远端错误码，成功时为空
 * @param errorMessage   已脱敏的错误说明，成功时为空
 * @param requestIssued  请求字节是否已经交给连接
 */
public record ExternalKnowledgeRemoval(
        boolean removed,
        int deletedCount,
        ExternalIndexFailureClass failureClass,
        String errorCode,
        String errorMessage,
        boolean requestIssued
) {

    public ExternalKnowledgeRemoval {
        deletedCount = Math.max(0, deletedCount);
        failureClass = failureClass == null ? ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT : failureClass;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        if (removed && !failureClass.success()) {
            throw new IllegalArgumentException("a successful removal must not carry a failure class");
        }
    }

    public static ExternalKnowledgeRemoval accepted(int deletedCount) {
        return new ExternalKnowledgeRemoval(true, deletedCount, ExternalIndexFailureClass.NONE, "", "", true);
    }

    public static ExternalKnowledgeRemoval failed(
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage,
            boolean requestIssued
    ) {
        return new ExternalKnowledgeRemoval(false, 0, failureClass, errorCode, errorMessage, requestIssued);
    }
}
