package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.ModelProviderCredentialStore;
import com.wish.rd.rag.project.agent.model.ModelProviderCredential;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory provider secret storage for focused tests and local configurations. */
public final class InMemoryModelProviderCredentialStore implements ModelProviderCredentialStore {

    private final ConcurrentMap<String, ModelProviderCredential> values = new ConcurrentHashMap<>();

    @Override
    public void save(String providerId, String secret, long updatedAtEpochMillis) {
        String id = text(providerId);
        values.put(id, new ModelProviderCredential(id, secret == null ? "" : secret, updatedAtEpochMillis));
    }

    @Override
    public void delete(String providerId) {
        values.remove(text(providerId));
    }

    @Override
    public Optional<ModelProviderCredential> find(String providerId) {
        return Optional.ofNullable(values.get(text(providerId)));
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
