package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.ModelProviderCredential;
import com.wish.rd.rag.project.agent.model.ModelProviderCredentialStatus;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Stores provider secrets off the public profile and resolves them by env name. */
public final class ModelProviderCredentialService {

    private final ModelProviderCredentialStore store;
    private final ModelProviderProfileStore profiles;

    public ModelProviderCredentialService(
            ModelProviderCredentialStore store,
            ModelProviderProfileStore profiles
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    }

    public void put(String providerId, String apiKey) {
        String id = requireText(providerId, "providerId");
        if (profiles.find(id).isEmpty()) {
            throw new IllegalArgumentException("provider profile not found: " + id);
        }
        String secret = apiKey == null ? "" : apiKey.strip();
        if (secret.isBlank()) {
            store.delete(id);
            return;
        }
        store.save(id, secret, System.currentTimeMillis());
    }

    public ModelProviderCredentialStatus status(String providerId) {
        String id = providerId == null ? "" : providerId.strip();
        Optional<ModelProviderCredential> stored = store.find(id);
        boolean configured = stored.filter(row -> row.secret() != null && !row.secret().isBlank()).isPresent();
        Long updatedAt = stored.map(ModelProviderCredential::updatedAtEpochMillis).orElse(null);
        return new ModelProviderCredentialStatus(id, configured, configured ? updatedAt : null);
    }

    public String resolveByEnvironmentVariable(String envName) {
        String env = envName == null ? "" : envName.strip().toUpperCase(Locale.ROOT);
        if (env.isBlank()) {
            return "";
        }
        ModelProviderCredential newest = null;
        for (ModelProviderProfile profile : profiles.list()) {
            if (!env.equals(profile.credentialEnvironmentVariable())) {
                continue;
            }
            Optional<ModelProviderCredential> stored = store.find(profile.providerId());
            if (stored.isEmpty()) {
                continue;
            }
            ModelProviderCredential candidate = stored.get();
            if (candidate.secret() == null || candidate.secret().isBlank()) {
                continue;
            }
            if (newest == null || candidate.updatedAtEpochMillis() > newest.updatedAtEpochMillis()) {
                newest = candidate;
            }
        }
        return newest == null ? "" : newest.secret();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
