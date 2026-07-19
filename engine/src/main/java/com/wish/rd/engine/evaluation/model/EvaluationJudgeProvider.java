package com.wish.rd.engine.evaluation.model;

/** Optional semantic judge implementation exposed by the Python scorer. */
public enum EvaluationJudgeProvider {
    NONE("none"),
    RAGAS("ragas"),
    OPENAI_COMPATIBLE("openai-compatible");

    private final String commandValue;

    EvaluationJudgeProvider(String commandValue) {
        this.commandValue = commandValue;
    }

    /** @return allowlisted CLI value */
    public String commandValue() {
        return commandValue;
    }
}
