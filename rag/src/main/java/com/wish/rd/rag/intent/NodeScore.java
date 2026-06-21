package com.wish.rd.rag.intent;

import java.util.List;

public record NodeScore(
        IntentNode node,
        double score,
        List<String> matchedTerms
) {

    public NodeScore {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }
}
