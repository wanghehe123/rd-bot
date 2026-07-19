package com.wish.rd.engine.requirement.review.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable, losslessly partitioned input package for one requirement-delivery AI review. */
public record AiReviewPackage(
        String taskId,
        List<AiReviewSource> sources,
        List<AiReviewPart> parts,
        int totalChars,
        int omittedSourceCount,
        String packageHash
) {
    public AiReviewPackage {
        taskId = taskId == null ? "" : taskId.strip();
        sources = sources == null ? List.of() : List.copyOf(sources);
        parts = parts == null ? List.of() : List.copyOf(parts);
        totalChars = Math.max(0, totalChars);
        omittedSourceCount = Math.max(0, omittedSourceCount);
        packageHash = packageHash == null ? "" : packageHash.strip();
    }

    public int partCount() {
        return parts.size();
    }

    public Set<String> sourceIds() {
        return sources.stream().map(AiReviewSource::sourceId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
