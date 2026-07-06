package com.wish.rd.rag.sample.model;

import java.util.List;

public record SampleQuestionPage(
        List<ManagedSampleQuestion> items,
        long total,
        int current,
        int size
) {

    public SampleQuestionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
