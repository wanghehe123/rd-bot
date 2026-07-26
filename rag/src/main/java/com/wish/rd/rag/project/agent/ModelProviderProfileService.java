package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.ModelProviderProfile;

import java.net.URI;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates provider metadata without accepting or storing secret values. */
public final class ModelProviderProfileService {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private final ModelProviderProfileStore store;

    public ModelProviderProfileService(ModelProviderProfileStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public ModelProviderProfile register(ModelProviderProfile profile) {
        validate(profile);
        return store.save(profile);
    }

    public Optional<ModelProviderProfile> find(String providerId) {
        String normalized = providerId == null ? "" : providerId.strip();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return store.find(normalized);
    }

    public List<ModelProviderProfile> list() {
        return store.list();
    }

    private static void validate(ModelProviderProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("provider profile must not be null");
        }
        requireText(profile.providerId(), "providerId");
        requireText(profile.displayName(), "displayName");
        requireUrl(profile.baseUrl());
        requireText(profile.modelId(), "modelId");
        String envName = requireText(profile.credentialEnvironmentVariable(), "credentialEnvironmentVariable");
        if (!ENV_NAME.matcher(envName).matches()) {
            throw new IllegalArgumentException("credentialEnvironmentVariable must be an env name, not a value");
        }
    }

    private static void requireUrl(String value) {
        String normalized = requireText(value, "baseUrl");
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl must be a valid absolute URL", exception);
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL");
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
