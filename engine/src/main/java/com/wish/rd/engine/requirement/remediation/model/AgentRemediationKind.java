package com.wish.rd.engine.requirement.remediation.model;

public enum AgentRemediationKind {
    QA_PRODUCT_FIX(2),
    QA_PROTOCOL_RETRY(1);

    private final int maximumRounds;

    AgentRemediationKind(int maximumRounds) {
        this.maximumRounds = maximumRounds;
    }

    public int maximumRounds() {
        return maximumRounds;
    }
}
