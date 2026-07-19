package com.wish.rd.engine.evaluation.model;

/** Record source supported by the existing Python evaluation runner. */
public enum EvaluationSource {
    FIXTURE("fixture"),
    RAG_HTTP("rag-http"),
    TASK_RUN("task-run");

    private final String commandValue;

    EvaluationSource(String commandValue) {
        this.commandValue = commandValue;
    }

    /** @return allowlisted CLI value */
    public String commandValue() {
        return commandValue;
    }
}
