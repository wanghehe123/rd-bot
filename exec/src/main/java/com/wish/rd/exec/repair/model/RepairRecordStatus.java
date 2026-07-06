package com.wish.rd.exec.repair.model;

/**
 * 修复记录状态：覆盖 P0 可持久化的任务生命周期节点，并补充 P1 工单接入所需的前置状态。
 *
 * <p>P1 新增：
 * <ul>
 *   <li>{@link #QUEUED}：工单事件已入队，等待消费。</li>
 *   <li>{@link #CONTEXT_COLLECTING}：正在拉取工单详情与消息。</li>
 *   <li>{@link #CONTEXT_READY}：RAG 上下文已就绪。</li>
 *   <li>{@link #WAITING_FOR_INFO}：工单信息不足，已向用户追问，等待补充。</li>
 * </ul>
 */
public enum RepairRecordStatus {
    CREATED,
    QUEUED,
    CONTEXT_COLLECTING,
    CONTEXT_READY,
    WAITING_FOR_INFO,
    RAG_READY,
    EXECUTING,
    VALIDATING,
    COMMITTED,
    MERGED,
    COMPLETED,
    FAILED
}
