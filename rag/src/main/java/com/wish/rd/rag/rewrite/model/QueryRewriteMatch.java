package com.wish.rd.rag.rewrite.model;

/** One rule that changed the text during a read-only rewrite preview. */
public record QueryRewriteMatch(
        String mappingId,
        String sourceTerm,
        String targetTerm,
        QueryTermMappingScope scope
) {

    public QueryRewriteMatch {
        mappingId = mappingId == null ? "" : mappingId;
        sourceTerm = sourceTerm == null ? "" : sourceTerm;
        targetTerm = targetTerm == null ? "" : targetTerm;
        scope = scope == null ? QueryTermMappingScope.GLOBAL : scope;
    }
}
