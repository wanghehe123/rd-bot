package com.wish.rd.engine.rag;

/**
 * RAG 检索评测日志输出端口。
 *
 * <p>Engine 只依赖该端口，文件、对象存储或远端评测系统由 bootstrap 等适配层实现。
 */
@FunctionalInterface
public interface RagRetrievalLogSink {

    /**
     * 追加一次 RAG 检索日志事件。
     *
     * @param event RAG 检索日志事件
     */
    void append(RagRetrievalLogEvent event);

    /**
     * 返回不执行任何写入的默认实现。
     *
     * @return 空操作日志 sink
     */
    static RagRetrievalLogSink noop() {
        return event -> {
        };
    }
}
