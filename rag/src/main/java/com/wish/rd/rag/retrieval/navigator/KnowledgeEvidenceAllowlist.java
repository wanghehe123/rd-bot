package com.wish.rd.rag.retrieval.navigator;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.retrieval.navigator.model.AdmittedKnowledgeEvidence;
import com.wish.rd.rag.retrieval.navigator.model.EvidenceRejectionReason;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;
import com.wish.rd.rag.retrieval.navigator.model.KnowledgeEvidenceAllowlistResult;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 以本地库为唯一权威的证据准入判定。只读绑定与文档，不写任何一行。
 *
 * <p>{@link KnowledgeDocument#visible()} 只覆盖软删除与 supersede，不含 {@code enabled}；
 * 第三步必须两者同时成立。文档行缺失视为非活跃，而不是「无绑定」——绑定已经解析成功。
 */
@Component
public final class KnowledgeEvidenceAllowlist {

    private final KnowledgeExternalIndexBindingStore bindings;
    private final KnowledgeDocumentStore documents;

    /**
     * @param bindings  投影绑定，只调用按 URI 反查
     * @param documents 逻辑文档，只调用 {@code findById}
     */
    public KnowledgeEvidenceAllowlist(
            KnowledgeExternalIndexBindingStore bindings,
            KnowledgeDocumentStore documents
    ) {
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
    }

    /**
     * 按固定顺序判定每条命中，第一步失败即停止并记账。
     *
     * @param hits             远端命中，空列表表示未召回而不是全部被拒
     * @param knowledgeBaseIds 本次检索允许的知识库
     * @return 通过集合与按原因计数的拒绝分布
     */
    public KnowledgeEvidenceAllowlistResult admit(
            List<ExternalNavigatorSearch.Hit> hits,
            Set<String> knowledgeBaseIds
    ) {
        List<ExternalNavigatorSearch.Hit> candidates = hits == null ? List.of() : hits;
        Set<String> scope = knowledgeBaseIds == null ? Set.of() : Set.copyOf(knowledgeBaseIds);
        List<AdmittedKnowledgeEvidence> admitted = new ArrayList<>();
        EnumMap<EvidenceRejectionReason, Integer> rejections = emptyTally();
        for (ExternalNavigatorSearch.Hit hit : candidates) {
            String uri = hit == null ? "" : hit.uri();
            Optional<KnowledgeExternalIndexBinding> resolved = bindings.findByProviderAndLongestRemoteUriPrefix(
                    KnowledgeExternalIndexBinding.OPENVIKING, uri);
            if (resolved.isEmpty()
                    || !KnowledgeExternalIndexBinding.OPENVIKING.equals(resolved.get().provider())) {
                rejections.merge(EvidenceRejectionReason.NO_BINDING, 1, Integer::sum);
                continue;
            }
            KnowledgeExternalIndexBinding binding = resolved.get();
            if (binding.projectionStatus() != ExternalKnowledgeProjectionStatus.IN_SYNC
                    || binding.observedVersion() != binding.desiredVersion()) {
                rejections.merge(EvidenceRejectionReason.VERSION_NOT_VERIFIED, 1, Integer::sum);
                continue;
            }
            Optional<KnowledgeDocument> document = documents.findById(binding.documentId());
            if (document.isEmpty() || !document.get().enabled() || !document.get().visible()) {
                rejections.merge(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE, 1, Integer::sum);
                continue;
            }
            if (!scope.contains(document.get().knowledgeBaseId())) {
                rejections.merge(EvidenceRejectionReason.OUT_OF_SCOPE, 1, Integer::sum);
                continue;
            }
            admitted.add(new AdmittedKnowledgeEvidence(hit, binding, document.get()));
        }
        return new KnowledgeEvidenceAllowlistResult(admitted, rejections);
    }

    private static EnumMap<EvidenceRejectionReason, Integer> emptyTally() {
        EnumMap<EvidenceRejectionReason, Integer> counts = new EnumMap<>(EvidenceRejectionReason.class);
        for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
            counts.put(reason, 0);
        }
        return counts;
    }
}
