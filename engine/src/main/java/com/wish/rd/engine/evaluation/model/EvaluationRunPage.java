package com.wish.rd.engine.evaluation.model;

import java.util.List;

/** One server-produced page of evaluation history plus global console counters. */
public record EvaluationRunPage(
        List<EvaluationRun> records,
        long total,
        int page,
        int pageSize,
        int pages,
        EvaluationRunOverview overview
) {
    public EvaluationRunPage {
        records = records == null ? List.of() : List.copyOf(records);
        total = Math.max(0L, total);
        page = Math.max(1, page);
        pageSize = Math.max(1, pageSize);
        pages = total == 0L ? 0 : (int) Math.ceil((double) total / pageSize);
        overview = overview == null ? new EvaluationRunOverview(0, 0, 0, 0, 0) : overview;
    }

    /** Creates a page using the normalized request values. */
    public static EvaluationRunPage of(
            List<EvaluationRun> records,
            long total,
            EvaluationRunQuery query,
            EvaluationRunOverview overview
    ) {
        return new EvaluationRunPage(records, total, query.page(), query.pageSize(), 0, overview);
    }
}
