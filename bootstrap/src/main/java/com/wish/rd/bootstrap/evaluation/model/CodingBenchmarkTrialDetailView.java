package com.wish.rd.bootstrap.evaluation.model;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent;

import java.util.List;

/**
 * Console-facing trial detail payload. Artifact paths are logical keys only — never host absolute paths.
 */
public record CodingBenchmarkTrialDetailView(
        CodingBenchmarkTrial trial,
        List<CodingBenchmarkTrialEvent> events,
        List<Artifact> artifacts,
        List<Preview> previews
) {
    public CodingBenchmarkTrialDetailView {
        events = events == null ? List.of() : List.copyOf(events);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        previews = previews == null ? List.of() : List.copyOf(previews);
    }

    public record Artifact(
            String key,
            String label,
            long sizeBytes,
            boolean available
    ) {}

    public record Preview(
            String key,
            String label,
            String content,
            boolean truncated
    ) {}

    public record ArtifactContent(
            String key,
            String label,
            String content,
            boolean truncated,
            long sizeBytes
    ) {}
}
