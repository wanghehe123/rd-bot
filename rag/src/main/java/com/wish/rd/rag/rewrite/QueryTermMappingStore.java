package com.wish.rd.rag.rewrite;

import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;

import java.util.List;
import java.util.Optional;

/** Durable source of truth for managed query term mappings. */
public interface QueryTermMappingStore {

    ManagedQueryTermMapping save(ManagedQueryTermMapping mapping);

    Optional<ManagedQueryTermMapping> findById(String id);

    List<ManagedQueryTermMapping> list();

    void delete(String id);
}
