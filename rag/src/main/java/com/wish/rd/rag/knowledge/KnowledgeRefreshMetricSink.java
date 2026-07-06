package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;


/**
 * Sink for knowledge refresh operation metrics.
 */
public interface KnowledgeRefreshMetricSink {

    /**
     * Publish one refresh metric.
     *
     * @param metric metric to publish
     */
    void publish(KnowledgeRefreshMetric metric);

    /**
     * Returns a no-op sink.
     *
     * @return no-op sink
     */
    static KnowledgeRefreshMetricSink noop() {
        return metric -> {
        };
    }
}
