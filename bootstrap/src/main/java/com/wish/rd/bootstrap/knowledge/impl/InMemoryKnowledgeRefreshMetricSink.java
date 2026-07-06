package com.wish.rd.bootstrap.knowledge.impl;

import com.wish.rd.rag.knowledge.model.KnowledgeRefreshMetric;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetricQueryPort;
import com.wish.rd.rag.knowledge.KnowledgeRefreshMetricSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-config knowledge refresh metric sink for local operations view.
 */
@Component
@ConditionalOnMissingBean(KnowledgeRefreshMetricSink.class)
public class InMemoryKnowledgeRefreshMetricSink implements KnowledgeRefreshMetricSink, KnowledgeRefreshMetricQueryPort {

    private final List<KnowledgeRefreshMetric> metrics = new ArrayList<>();

    @Override
    public synchronized void publish(KnowledgeRefreshMetric metric) {
        metrics.add(Objects.requireNonNull(metric, "metric must not be null"));
    }

    /**
     * Returns an immutable snapshot of refresh metrics.
     *
     * @return metric snapshot
     */
    public synchronized List<KnowledgeRefreshMetric> metrics() {
        return List.copyOf(metrics);
    }
}
