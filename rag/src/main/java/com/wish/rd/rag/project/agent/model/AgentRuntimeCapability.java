package com.wish.rd.rag.project.agent.model;

import java.util.Locale;

/** Closed set of opt-in runtime protocol capabilities frozen into a stage snapshot. */
public enum AgentRuntimeCapability {
    PI_AGENT_STATE_V2,
    PI_QA_REMEDIATION_V2;

    public static AgentRuntimeCapability parse(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        try {
            return AgentRuntimeCapability.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported agent runtime capability: " + normalized, exception);
        }
    }
}
