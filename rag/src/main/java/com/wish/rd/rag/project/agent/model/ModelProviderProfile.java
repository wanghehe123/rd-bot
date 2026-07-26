package com.wish.rd.rag.project.agent.model;

import java.util.Locale;

/** Public provider routing metadata; credentials are resolved by env name at execution time. */
public record ModelProviderProfile(
        String providerId,
        String displayName,
        ModelProviderProtocol protocol,
        String baseUrl,
        String modelId,
        String credentialEnvironmentVariable,
        boolean authHeader,
        boolean enabled,
        long version
) {

    public ModelProviderProfile {
        providerId = text(providerId);
        displayName = text(displayName);
        if (protocol == null) {
            throw new IllegalArgumentException("protocol must not be null");
        }
        baseUrl = text(baseUrl);
        modelId = text(modelId);
        credentialEnvironmentVariable = text(credentialEnvironmentVariable)
                .toUpperCase(Locale.ROOT);
        version = version <= 0L ? 1L : version;
    }

    /** Deliberately never exposes a credential value. */
    public String credentialValue() {
        return "";
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
