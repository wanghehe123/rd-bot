package com.wish.rd.rag.rewrite.model;

public record ManagedQueryTermMapping(
        String id,
        String sourceTerm,
        String targetTerm,
        int priority,
        boolean enabled,
        String remark
) {

    public ManagedQueryTermMapping {
        id = id == null ? "" : id;
        sourceTerm = sourceTerm == null ? "" : sourceTerm;
        targetTerm = targetTerm == null ? "" : targetTerm;
        remark = remark == null ? "" : remark;
    }

    public QueryTermMapping toRewriteMapping() {
        return new QueryTermMapping(sourceTerm, targetTerm, priority, enabled);
    }
}
