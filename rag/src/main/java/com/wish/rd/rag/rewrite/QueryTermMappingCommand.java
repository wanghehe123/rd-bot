package com.wish.rd.rag.rewrite;

public record QueryTermMappingCommand(
        String sourceTerm,
        String targetTerm,
        int priority,
        boolean enabled,
        String remark
) {

    public QueryTermMappingCommand {
        sourceTerm = sourceTerm == null ? "" : sourceTerm.strip();
        targetTerm = targetTerm == null ? "" : targetTerm.strip();
        remark = remark == null ? "" : remark.strip();
    }
}
