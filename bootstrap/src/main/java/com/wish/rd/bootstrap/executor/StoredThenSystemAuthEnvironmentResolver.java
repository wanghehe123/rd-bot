package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.docker.AuthEnvironmentResolver;
import com.wish.rd.rag.project.agent.ModelProviderCredentialService;

import java.util.Objects;

/**
 * Resolves provider secrets from the credential store first, then process env / launchctl.
 */
public final class StoredThenSystemAuthEnvironmentResolver
        implements AuthEnvironmentResolver {

    private final ModelProviderCredentialService credentials;
    private final AuthEnvironmentResolver fallback;

    public StoredThenSystemAuthEnvironmentResolver(
            ModelProviderCredentialService credentials,
            AuthEnvironmentResolver fallback
    ) {
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    @Override
    public String resolve(String envName) {
        String stored = credentials.resolveByEnvironmentVariable(envName);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String fromFallback = fallback.resolve(envName);
        return fromFallback == null ? "" : fromFallback;
    }
}
