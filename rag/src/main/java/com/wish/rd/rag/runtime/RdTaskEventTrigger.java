package com.wish.rd.rag.runtime;

/**
 * RD 任务状态事件的触发来源。
 *
 * <p>用于区分状态机推进与管理台人工操作，供 {@link RagStreamTaskRegistry} 写入事件时标记。
 */
public enum RdTaskEventTrigger {
    /** 经由运行时编排自动推进（RAG 检索、执行器、PR 回写等）。 */
    SYSTEM,
    /** 管理台 REST API 直接触发（新建、修改、暂停、恢复、删除）。 */
    API,
    /** 其它人工编排通道（测试通道、脚本）。 */
    MANUAL
}
