package com.wish.rd.rag.sample.model;

public record ManagedSampleQuestion(
        String id,
        String title,
        String description,
        String question,
        long createTime,
        long updateTime
) {
}
