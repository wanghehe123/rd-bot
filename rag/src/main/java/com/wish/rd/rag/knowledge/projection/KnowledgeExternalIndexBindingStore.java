package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;

import java.util.List;
import java.util.Optional;

/**
 * 外部索引 desired/observed 绑定。
 */
public interface KnowledgeExternalIndexBindingStore {

    KnowledgeExternalIndexBinding save(KnowledgeExternalIndexBinding binding);

    Optional<KnowledgeExternalIndexBinding> findByProviderAndDocumentId(String provider, String documentId);

    List<KnowledgeExternalIndexBinding> listAll();

    void delete(String provider, String documentId);
}
