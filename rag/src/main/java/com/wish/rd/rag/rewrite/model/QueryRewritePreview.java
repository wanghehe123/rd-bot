package com.wish.rd.rag.rewrite.model;

import java.util.List;

/** Read-only result of applying the effective query rewrite rules. */
public record QueryRewritePreview(
        String originalText,
        String rewrittenText,
        List<QueryRewriteMatch> matches
) {

    public QueryRewritePreview {
        originalText = originalText == null ? "" : originalText;
        rewrittenText = rewrittenText == null ? "" : rewrittenText;
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}
