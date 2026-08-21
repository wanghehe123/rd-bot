package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.ModelProviderCredential;

import java.util.Optional;

/** Persists provider secrets separately from routing metadata. */
public interface ModelProviderCredentialStore {

    void save(String providerId, String secret, long updatedAtEpochMillis);

    void delete(String providerId);

    Optional<ModelProviderCredential> find(String providerId);
}
