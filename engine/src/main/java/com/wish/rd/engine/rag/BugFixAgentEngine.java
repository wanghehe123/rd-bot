package com.wish.rd.engine.rag;

/**
 * Bug 修复执行引擎端口。
 *
 * <p>P0 只定义接口边界，具体执行器在后续阶段实现。
 */
public interface BugFixAgentEngine {

    void submit(BugFixMessage message);
}
