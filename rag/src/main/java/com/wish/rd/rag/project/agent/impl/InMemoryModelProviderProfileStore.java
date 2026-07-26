package com.wish.rd.rag.project.agent.impl;

import com.wish.rd.rag.project.agent.ModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory provider profile storage for focused tests and local configurations. */
public final class InMemoryModelProviderProfileStore implements ModelProviderProfileStore {

    private final ConcurrentMap<String, ModelProviderProfile> values = new ConcurrentHashMap<>();

    @Override
    public ModelProviderProfile save(ModelProviderProfile profile) {
        values.put(profile.providerId(), profile);
        return profile;
    }

    @Override
    public Optional<ModelProviderProfile> find(String providerId) {
        return Optional.ofNullable(values.get(providerId == null ? "" : providerId.strip()));
    }

    @Override
    public List<ModelProviderProfile> list() {
        return values.values().stream()
                .sorted(Comparator.comparing(ModelProviderProfile::providerId))
                .toList();
    }
}
