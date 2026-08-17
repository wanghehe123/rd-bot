package com.wish.rd.rag.project.agent.model;

import java.util.List;

/** List payload for the project agent-strategy console. */
public record AgentStrategyConsole(
        String projectId,
        String defaultStrategyId,
        boolean synthesizedFromLegacy,
        List<AgentStrategyProfile> strategies
) {

    public AgentStrategyConsole {
        projectId = projectId == null ? "" : projectId.strip();
        defaultStrategyId = defaultStrategyId == null ? "" : defaultStrategyId.strip();
        strategies = List.copyOf(strategies == null ? List.of() : strategies);
    }
}
