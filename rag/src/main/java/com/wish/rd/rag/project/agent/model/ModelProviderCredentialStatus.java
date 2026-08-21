package com.wish.rd.rag.project.agent.model;

/** Public credential status; must never include the secret in fields or {@code toString()}. */
public record ModelProviderCredentialStatus(
        String providerId,
        boolean configured,
        Long updatedAtEpochMillis
) {
}
