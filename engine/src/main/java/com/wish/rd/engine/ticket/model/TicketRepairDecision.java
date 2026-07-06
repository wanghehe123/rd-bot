package com.wish.rd.engine.ticket.model;

/**
 * 工单修复决策：消费端拿到工单快照后，先做一次本地决策。
 *
 * <p>决策结果决定是否进入 RAG、忽略已关闭工单、或转入等待信息状态。
 */
public enum TicketRepairDecision {

    /** 工单字段充足，进入 RAG 上下文准备。 */
    READY_FOR_RAG,

    /** 关键字段缺失（无标题/描述/日志/仓库），需要向用户追问后等待补充。 */
    WAITING_FOR_INFO,

    /** 工单已关闭/已解决，忽略本次事件并记录为终态。 */
    IGNORED_CLOSED,

    /** 处理失败（如工单不存在或下游异常）。 */
    FAILED
}
