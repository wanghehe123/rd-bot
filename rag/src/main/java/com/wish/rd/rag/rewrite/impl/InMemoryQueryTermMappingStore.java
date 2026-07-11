package com.wish.rd.rag.rewrite.impl;

import com.wish.rd.rag.rewrite.QueryTermMappingStore;
import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** In-memory store reserved for tests and explicit non-production runtime. */
public final class InMemoryQueryTermMappingStore implements QueryTermMappingStore {

    private final LinkedHashMap<String, ManagedQueryTermMapping> mappings = new LinkedHashMap<>();

    @Override
    public synchronized ManagedQueryTermMapping save(ManagedQueryTermMapping mapping) {
        mappings.put(mapping.id(), mapping);
        return mapping;
    }

    @Override
    public synchronized Optional<ManagedQueryTermMapping> findById(String id) {
        return Optional.ofNullable(mappings.get(id));
    }

    @Override
    public synchronized List<ManagedQueryTermMapping> list() {
        return List.copyOf(mappings.values());
    }

    @Override
    public synchronized void delete(String id) {
        mappings.remove(id);
    }
}
