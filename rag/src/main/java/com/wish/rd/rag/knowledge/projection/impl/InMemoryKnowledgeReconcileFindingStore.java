package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.rag.knowledge.projection.KnowledgeReconcileFindingStore;
import com.wish.rd.rag.knowledge.projection.model.ReconcileFinding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 内存对账发现账本。身份键吸收重复扫描，避免一轮漂移刷出一堆新行。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeReconcileFindingStore implements KnowledgeReconcileFindingStore {

    private final LinkedHashMap<String, ReconcileFinding> findings = new LinkedHashMap<>();

    @Override
    public synchronized ReconcileFinding upsert(ReconcileFinding finding) {
        String key = identity(finding);
        ReconcileFinding current = findings.get(key);
        if (current == null) {
            findings.put(key, finding);
            return finding;
        }
        ReconcileFinding merged = new ReconcileFinding(
                current.id(),
                current.provider(),
                current.knowledgeBaseId(),
                current.findingType(),
                current.remoteUri(),
                current.documentId(),
                finding.detail().isBlank() ? current.detail() : finding.detail(),
                finding.status(),
                current.firstSeenAtEpochMillis(),
                Math.max(current.lastSeenAtEpochMillis(), finding.lastSeenAtEpochMillis())
        );
        findings.put(key, merged);
        return merged;
    }

    @Override
    public synchronized List<ReconcileFinding> listByKnowledgeBase(String provider, String knowledgeBaseId) {
        String normalized = provider == null || provider.isBlank() ? "OPENVIKING" : provider.strip().toUpperCase();
        return findings.values().stream()
                .filter(finding -> finding.provider().equals(normalized)
                        && finding.knowledgeBaseId().equals(knowledgeBaseId))
                .toList();
    }

    private static String identity(ReconcileFinding finding) {
        return finding.provider() + "\0"
                + finding.knowledgeBaseId() + "\0"
                + finding.findingType().name() + "\0"
                + finding.remoteUri() + "\0"
                + finding.documentId();
    }
}
