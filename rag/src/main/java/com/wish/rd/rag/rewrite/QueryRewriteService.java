package com.wish.rd.rag.rewrite;

import com.wish.rd.framework.convention.ChatMessage;

import java.util.List;

public interface QueryRewriteService {

    String rewrite(String userQuestion);

    default RewriteResult rewriteWithSplit(String userQuestion) {
        String rewritten = rewrite(userQuestion);
        return new RewriteResult(rewritten, List.of(rewritten));
    }

    default RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        return rewriteWithSplit(userQuestion);
    }
}
