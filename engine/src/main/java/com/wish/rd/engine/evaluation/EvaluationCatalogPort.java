package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationCapabilities;

/** Supplies server-owned evaluation datasets and safe Web form capabilities. */
@FunctionalInterface
public interface EvaluationCatalogPort {
    EvaluationCapabilities capabilities();
}
