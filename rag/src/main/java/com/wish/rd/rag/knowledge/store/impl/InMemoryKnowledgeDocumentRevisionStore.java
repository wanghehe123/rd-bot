package com.wish.rd.rag.knowledge.store.impl;

import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 内存 revision Store，供单测与无数据库启动使用。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeDocumentRevisionStore implements KnowledgeDocumentRevisionStore {

    private final LinkedHashMap<String, KnowledgeDocumentRevision> revisions = new LinkedHashMap<>();

    @Override
    public synchronized KnowledgeDocumentRevision save(KnowledgeDocumentRevision revision) {
        revisions.put(revision.id(), revision);
        return revision;
    }

    @Override
    public synchronized Optional<KnowledgeDocumentRevision> findByDocumentIdAndChecksum(
            String documentId,
            String checksum
    ) {
        return revisions.values().stream()
                .filter(revision -> revision.documentId().equals(documentId))
                .filter(revision -> revision.checksum().equals(checksum))
                .findFirst();
    }

    @Override
    public synchronized List<KnowledgeDocumentRevision> listByDocumentId(String documentId) {
        ArrayList<KnowledgeDocumentRevision> found = new ArrayList<>();
        for (KnowledgeDocumentRevision revision : revisions.values()) {
            if (revision.documentId().equals(documentId)) {
                found.add(revision);
            }
        }
        found.sort((left, right) -> Long.compare(left.syncVersion(), right.syncVersion()));
        return List.copyOf(found);
    }
}
