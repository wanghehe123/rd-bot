package com.wish.rd.rag.project.agent.model;

/** Service-internal provider secret. Never copy this onto an HTTP DTO. */
public record ModelProviderCredential(String providerId, String secret, long updatedAtEpochMillis) {
}
