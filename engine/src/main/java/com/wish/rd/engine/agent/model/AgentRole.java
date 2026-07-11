package com.wish.rd.engine.agent.model;

import java.util.List;

/**
 * RD 任务阶段编排中的执行角色。
 *
 * <p>供 engine 控制面规划阶段运行，不代表底层模型 provider 或具体执行器。
 */
public enum AgentRole {
    REQUIREMENT_REVIEWER,
    SOLUTION_ARCHITECT,
    CODING_AGENT,
    QA_AGENT,
    BUG_EVIDENCE_COLLECTOR,
    BUG_RAG_RETRIEVER,
    BUG_ACCEPTANCE_PLANNER,
    BUG_CODING_AGENT;

    /**
     * 返回需求交付默认角色顺序。
     *
     * @return 默认角色顺序
     */
    public static List<AgentRole> requirementDeliveryOrder() {
        return List.of(REQUIREMENT_REVIEWER, SOLUTION_ARCHITECT, CODING_AGENT, QA_AGENT);
    }

    /**
     * 返回 Bug 修复默认阶段顺序。
     *
     * @return Bug 修复阶段顺序
     */
    public static List<AgentRole> bugFixOrder() {
        return List.of(BUG_EVIDENCE_COLLECTOR, BUG_RAG_RETRIEVER, BUG_ACCEPTANCE_PLANNER, BUG_CODING_AGENT);
    }
}
