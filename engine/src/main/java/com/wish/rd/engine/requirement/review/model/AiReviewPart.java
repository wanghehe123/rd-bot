package com.wish.rd.engine.requirement.review.model;

import java.util.List;

/** One bounded model-input part that covers one or more package sources. */
public record AiReviewPart(
        int partNo,
        List<String> sourceIds,
        String content,
        int charCount
) {
    public AiReviewPart {
        if (partNo <= 0) {
            throw new IllegalArgumentException("partNo must be positive");
        }
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        content = content == null ? "" : content;
        charCount = Math.max(0, charCount);
    }
}
