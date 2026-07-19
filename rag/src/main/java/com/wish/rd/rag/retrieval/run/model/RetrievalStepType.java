package com.wish.rd.rag.retrieval.run.model;

/** Stable retrieval step names used by the state timeline and persisted artifacts. */
public enum RetrievalStepType {
    NORMALIZE_QUERY,
    ROUTE_SCOPE,
    LOAD_KNOWLEDGE_MAP,
    PLAN_RETRIEVAL,
    SEARCH_CHANNEL,
    EXPAND_DOCUMENT,
    FUSE,
    RERANK,
    GRADE_EVIDENCE,
    PACKAGE_CONTEXT
}
