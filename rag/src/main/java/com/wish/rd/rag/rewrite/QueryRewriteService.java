package com.wish.rd.rag.rewrite;

import com.wish.rd.framework.convention.model.ChatMessage;

import java.util.List;
import com.wish.rd.rag.rewrite.model.RewriteResult;

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
