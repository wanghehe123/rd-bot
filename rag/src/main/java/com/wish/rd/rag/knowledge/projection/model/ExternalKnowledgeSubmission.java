package com.wish.rd.rag.knowledge.projection.model;

/**
 * add_resource 的受理结果。HTTP 2xx 只代表受理，检索就绪由后台任务与版本核验决定。
 *
 * @param failureClass 结果分类，{@link ExternalIndexFailureClass#NONE} 表示受理成功
 * @param remoteTaskId 远端后台任务 ID，受理成功时必须非空
 * @param rootUri      远端返回的资源根，必须与请求的 {@code to} 精确相等
 * @param errorCode    远端错误码，成功时为空
 * @param errorMessage 已脱敏的错误说明，成功时为空
 */
public record ExternalKnowledgeSubmission(
        ExternalIndexFailureClass failureClass,
        String remoteTaskId,
        String rootUri,
        String errorCode,
        String errorMessage
) {

    public ExternalKnowledgeSubmission {
        failureClass = failureClass == null ? ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT : failureClass;
        remoteTaskId = remoteTaskId == null ? "" : remoteTaskId;
        rootUri = rootUri == null ? "" : rootUri;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    /**
     * 构造一次受理成功的结果。
     *
     * @param remoteTaskId 远端任务 ID
     * @param rootUri      远端返回的资源根
     * @return 受理成功结果
     */
    public static ExternalKnowledgeSubmission accepted(String remoteTaskId, String rootUri) {
        return new ExternalKnowledgeSubmission(ExternalIndexFailureClass.NONE, remoteTaskId, rootUri, "", "");
    }

    /**
     * 构造一次失败结果。
     *
     * @param failureClass 失败分类
     * @param errorCode    远端错误码
     * @param errorMessage 已脱敏的错误说明
     * @return 失败结果
     */
    public static ExternalKnowledgeSubmission failed(
            ExternalIndexFailureClass failureClass,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalKnowledgeSubmission(failureClass, "", "", errorCode, errorMessage);
    }

    public boolean accepted() {
        return failureClass.success();
    }
}
