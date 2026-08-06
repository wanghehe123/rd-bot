package com.wish.rd.engine.requirement.publication.impl;

import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe in-memory publication ledger used by focused tests and local demos.
 */
public final class InMemoryRequirementPublicationStore implements RequirementPublicationStore {

    private final Map<String, RequirementPublication> byOperationId = new LinkedHashMap<>();
    private final Map<String, RequirementPublication> byId = new LinkedHashMap<>();

    @Override
    public synchronized RequirementPublication insertPrepared(RequirementPublication publication) {
        RequirementPublication existing = byOperationId.get(publication.operationId());
        if (existing != null) {
            return existing;
        }
        byOperationId.put(publication.operationId(), publication);
        byId.put(publication.id(), publication);
        return publication;
    }

    @Override
    public synchronized Optional<RequirementPublication> findByOperationId(String operationId) {
        return Optional.ofNullable(byOperationId.get(safe(operationId)));
    }

    @Override
    public synchronized List<RequirementPublication> findDueForReconcile(long beforeEpochMillis, int limit) {
        int capped = Math.max(0, limit);
        List<RequirementPublication> due = new ArrayList<>();
        for (RequirementPublication publication : byOperationId.values()) {
            if (publication.status() != RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT) {
                continue;
            }
            if (publication.nextReconcileAtEpochMillis() <= 0L
                    || publication.nextReconcileAtEpochMillis() > beforeEpochMillis) {
                continue;
            }
            due.add(publication);
            if (due.size() >= capped) {
                break;
            }
        }
        return List.copyOf(due);
    }

    @Override
    public synchronized RequirementPublication save(RequirementPublication publication) {
        RequirementPublication current = byOperationId.get(publication.operationId());
        if (current == null) {
            throw new IllegalStateException("publication not found: " + publication.operationId());
        }
        if (!current.id().equals(publication.id())) {
            throw new IllegalStateException("publication id mismatch for operation: " + publication.operationId());
        }
        byOperationId.put(publication.operationId(), publication);
        byId.put(publication.id(), publication);
        return publication;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
