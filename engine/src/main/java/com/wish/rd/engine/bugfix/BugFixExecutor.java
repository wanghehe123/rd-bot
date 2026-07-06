package com.wish.rd.engine.bugfix;

import com.wish.rd.engine.bugfix.model.BugFixExecutionRequest;
import com.wish.rd.engine.bugfix.model.BugFixExecutionResult;


/**
 * Bug 修复执行端口。
 *
 * <p>engine 只依赖该端口；Docker Claude Code、代码平台和真实产物解析由后续适配器实现。
 */
@FunctionalInterface
public interface BugFixExecutor {

    /**
     * 执行 Bug 修复任务。
     *
     * @param request 执行请求
     * @return 结构化执行结果
     */
    BugFixExecutionResult execute(BugFixExecutionRequest request);

    /**
     * 创建 mock 执行器。
     *
     * @return 返回固定结构化结果的执行器
     */
    static BugFixExecutor mock() {
        return request -> new BugFixExecutionResult(
                request.taskId(),
                "mock bug fix task",
                "mock executor finished without touching external systems",
                "https://github.example/rd-bot/pull/mock-" + request.taskId(),
                """
                {"status":"success","executor":"mock","taskId":"%s"}
                """.formatted(request.taskId()).strip()
        );
    }
}
