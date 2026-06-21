package com.wish.rd.adapter;

import java.util.List;

/**
 * 日志中心访问端口：按 {@link LogQuery} 检索目标系统的运行日志。
 *
 * <p>RAG 能力层只定义契约，具体实现由外部适配器（当前为测试通道的 mock）提供。
 * 被 {@link com.wish.rd.rag.retrieval.LogCenterSearchChannel} 用于拉取"运行日志"证据。
 */
public interface LogCenterPort {

    /**
     * 检索日志。
     *
     * @param query 日志查询条件（系统 ID、关键词、时间范围、topK）
     * @return 命中的日志行列表
     */
    List<String> searchLogs(LogQuery query);
}
