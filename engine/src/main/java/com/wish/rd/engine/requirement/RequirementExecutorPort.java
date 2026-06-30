package com.wish.rd.engine.requirement;

/**
 * 需求交付执行端口。
 */
@FunctionalInterface
public interface RequirementExecutorPort {

    /**
     * 执行需求交付任务。
     *
     * @param request 执行请求
     * @return 执行结果
     */
    RequirementExecutionResult execute(RequirementExecutionRequest request);

    /**
     * 创建不可用执行器。
     *
     * @return 返回失败结果的执行器
     */
    static RequirementExecutorPort unavailable() {
        return unavailable("requirement executor is unavailable");
    }

    /**
     * 创建带失败原因的不可用执行器。
     *
     * @param reason 不可用原因
     * @return 返回失败结果的执行器
     */
    static RequirementExecutorPort unavailable(String reason) {
        String message = reason == null || reason.isBlank()
                ? "requirement executor is unavailable"
                : reason.strip();
        return request -> RequirementExecutionResult.failure(
                request == null ? "" : request.taskId(),
                message,
                "{\"status\":\"FAILED\",\"errorMessage\":\"" + escapeJson(message) + "\"}"
        );
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
