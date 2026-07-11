package com.wish.rd.rag.rewrite.model;

public record ManagedQueryTermMapping(
        String id,
        String projectId,
        QueryTermMappingScope scope,
        String sourceTerm,
        String targetTerm,
        int priority,
        boolean enabled,
        String remark,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public ManagedQueryTermMapping {
        id = id == null ? "" : id;
        projectId = projectId == null ? "" : projectId.strip();
        scope = scope == null ? QueryTermMappingScope.GLOBAL : scope;
        sourceTerm = sourceTerm == null ? "" : sourceTerm;
        targetTerm = targetTerm == null ? "" : targetTerm;
        remark = remark == null ? "" : remark;
    }

    /** Compatibility constructor for pre-project global rule fixtures. */
    public ManagedQueryTermMapping(
            String id,
            String sourceTerm,
            String targetTerm,
            int priority,
            boolean enabled,
            String remark
    ) {
        this(id, "", QueryTermMappingScope.GLOBAL, sourceTerm, targetTerm, priority, enabled, remark, 0L, 0L);
    }

    public QueryTermMapping toRewriteMapping() {
        return new QueryTermMapping(sourceTerm, targetTerm, priority, enabled);
    }
}
