package com.wish.rd.rag.project.agent;

import com.wish.rd.rag.project.agent.model.ModelProviderProfile;

import java.util.List;
import java.util.Optional;

/** Persistence/configuration port for unified provider routing profiles. */
public interface ModelProviderProfileStore {

    ModelProviderProfile save(ModelProviderProfile profile);

    Optional<ModelProviderProfile> find(String providerId);

    default List<ModelProviderProfile> list() {
        return List.of();
    }
}
