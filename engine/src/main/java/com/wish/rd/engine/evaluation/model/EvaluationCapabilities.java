package com.wish.rd.engine.evaluation.model;

import java.util.List;

/** Read-only capabilities used to build the Web evaluation form safely. */
public record EvaluationCapabilities(
        boolean enabled,
        List<EvaluationSource> sources,
        List<EvaluationJudgeProvider> judgeProviders,
        List<EvaluationDataset> datasets,
        String defaultBaseUrl,
        int maxSampleLimit,
        int maxTimeoutSeconds,
        List<CodingBenchmarkSnapshot> codingBenchmarkSnapshots
) {
    public EvaluationCapabilities {
        sources = sources == null ? List.of() : List.copyOf(sources);
        judgeProviders = judgeProviders == null ? List.of() : List.copyOf(judgeProviders);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl;
        codingBenchmarkSnapshots = codingBenchmarkSnapshots == null ? List.of() : List.copyOf(codingBenchmarkSnapshots);
    }

    /** Backward-compatible capability shape for callers that do not expose coding benchmark snapshots. */
    public EvaluationCapabilities(
            boolean enabled,
            List<EvaluationSource> sources,
            List<EvaluationJudgeProvider> judgeProviders,
            List<EvaluationDataset> datasets,
            String defaultBaseUrl,
            int maxSampleLimit,
            int maxTimeoutSeconds
    ) {
        this(enabled, sources, judgeProviders, datasets, defaultBaseUrl, maxSampleLimit, maxTimeoutSeconds, List.of());
    }
}
