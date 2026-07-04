package com.wish.rd.engine.agent;

import java.util.List;

/**
 * 需求交付多 Agent 编排中的角色。
 *
 * <p>供 engine 控制面规划阶段运行，不代表底层模型 provider 或具体执行器。
 */
public enum AgentRole {
    REQUIREMENT_REVIEWER,
    SOLUTION_ARCHITECT,
    CODING_AGENT,
    QA_AGENT;

    /**
     * 返回需求交付默认角色顺序。
     *
     * @return 默认角色顺序
     */
    public static List<AgentRole> requirementDeliveryOrder() {
        return List.of(REQUIREMENT_REVIEWER, SOLUTION_ARCHITECT, CODING_AGENT, QA_AGENT);
    }
}
