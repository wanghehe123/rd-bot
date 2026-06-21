package com.wish.rd.rag.pipeline;

/**
 * 修复任务上下文交接端口。
 *
 * <p>定义 RAG 能力层向"执行层（exec）"交接上下文的边界。当前 exec 模块尚未落地，
 * 这里仅保留函数式接口作为设计预留位；{@link RepairRagPipeline} 在打包完上下文后会回调此端口。
 */
@FunctionalInterface
public interface RepairTaskContextPort {

    /**
     * 接收已打包好的修复上下文，由执行层决定如何消费（例如落库、调度修复任务等）。
     *
     * @param contextPackage RAG 主流程产出的上下文
     */
    void accept(RepairContextPackage contextPackage);
}
