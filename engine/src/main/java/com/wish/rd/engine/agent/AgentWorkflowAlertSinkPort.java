package com.wish.rd.engine.agent;

/**
 * 多 Agent 工作流告警输出端口。
 */
@FunctionalInterface
public interface AgentWorkflowAlertSinkPort {

    /**
     * 发布告警。
     *
     * @param alert 告警事件
     */
    void publish(AgentWorkflowAlert alert);

    /**
     * 返回默认空告警端口。
     *
     * @return 空实现
     */
    static AgentWorkflowAlertSinkPort noop() {
        return alert -> {
        };
    }
}
