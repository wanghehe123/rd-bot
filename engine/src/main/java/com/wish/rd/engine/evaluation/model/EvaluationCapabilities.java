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
        int maxTimeoutSeconds
) {
    public EvaluationCapabilities {
        sources = sources == null ? List.of() : List.copyOf(sources);
        judgeProviders = judgeProviders == null ? List.of() : List.copyOf(judgeProviders);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl;
    }
}
