package com.wish.rd.rag.knowledge;

import java.util.List;
import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;

/**
 * Query port for knowledge refresh operation metrics.
 */
public interface KnowledgeRefreshMetricQueryPort {

    /**
     * Lists refresh metrics.
     *
     * @return metric snapshot
     */
    List<KnowledgeRefreshMetric> metrics();
}
