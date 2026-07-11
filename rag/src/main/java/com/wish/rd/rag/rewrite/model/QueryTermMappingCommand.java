package com.wish.rd.rag.rewrite.model;

public record QueryTermMappingCommand(
        String projectId,
        QueryTermMappingScope scope,
        String sourceTerm,
        String targetTerm,
        int priority,
        boolean enabled,
        String remark
) {

    public QueryTermMappingCommand {
        projectId = projectId == null ? "" : projectId.strip();
        scope = scope == null ? QueryTermMappingScope.GLOBAL : scope;
        sourceTerm = sourceTerm == null ? "" : sourceTerm.strip();
        targetTerm = targetTerm == null ? "" : targetTerm.strip();
        remark = remark == null ? "" : remark.strip();
    }

    /** Compatibility constructor for existing global mapping clients. */
    public QueryTermMappingCommand(
            String sourceTerm,
            String targetTerm,
            int priority,
            boolean enabled,
            String remark
    ) {
        this("", QueryTermMappingScope.GLOBAL, sourceTerm, targetTerm, priority, enabled, remark);
    }
}
