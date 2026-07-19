package com.wish.rd.rag.qa.impl;

import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfile;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared in-memory QA profile store for local and test runtimes. */
public final class InMemoryQaValidationProfileStore implements QaValidationProfileStore {

    private final ConcurrentMap<String, QaValidationProfile> profiles = new ConcurrentHashMap<>();

    @Override
    public QaValidationProfile save(QaValidationProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        profiles.put(key(profile.scopeType(), profile.scopeId()), profile);
        return profile;
    }

    @Override
    public Optional<QaValidationProfile> find(String scopeType, String scopeId) {
        return Optional.ofNullable(profiles.get(key(scopeType, scopeId)));
    }

    private static String key(String scopeType, String scopeId) {
        String normalizedScope = scopeType == null ? "" : scopeType.strip().toUpperCase(Locale.ROOT);
        String normalizedId = scopeId == null ? "" : scopeId.strip();
        if (normalizedScope.isBlank() || normalizedId.isBlank()) {
            throw new IllegalArgumentException("scopeType and scopeId must not be blank");
        }
        return normalizedScope + ":" + normalizedId;
    }
}
