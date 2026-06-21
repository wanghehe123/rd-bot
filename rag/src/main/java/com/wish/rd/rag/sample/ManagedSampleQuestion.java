package com.wish.rd.rag.sample;

public record ManagedSampleQuestion(
        String id,
        String title,
        String description,
        String question,
        long createTime,
        long updateTime
) {
}
