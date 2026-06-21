package com.wish.rd.rag.rewrite;

import java.util.List;

public record RewriteResult(
        String rewrittenQuestion,
        List<String> subQuestions
) {

    public RewriteResult {
        rewrittenQuestion = rewrittenQuestion == null ? "" : rewrittenQuestion;
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }
}
