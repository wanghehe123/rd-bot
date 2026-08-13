package com.wish.rd.rag.retrieval.navigator;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.retrieval.navigator.model.AdmittedKnowledgeEvidence;
import com.wish.rd.rag.retrieval.navigator.model.EvidenceRejectionReason;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;
import com.wish.rd.rag.retrieval.navigator.model.KnowledgeEvidenceAllowlistResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据 allowlist 四步判定：先失败先记账，拒绝必须按原因可观测。
 */
class KnowledgeEvidenceAllowlistTest {

    private static final String SCOPE_KB = "7475766492030701568";
    private static final String OTHER_KB = "7474332748799414272";
    private static final String DOC = "7474332749017518080";
    private static final String OTHER_DOC = "7474342410068299776";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void shouldRejectWhenRemoteUriHasNoBinding() {
        Fixture fixture = Fixture.empty();
        fixture.documents.save(activeDocument(DOC, SCOPE_KB), "body");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC) + "/.abstract.md")),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.NO_BINDING));
        assertEquals(1, result.rejectedCount());
    }

    @Test
    void shouldRejectWhenBindingIsNotInSync() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.PROCESSING, 2L, 2L, true, 0L, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.VERSION_NOT_VERIFIED));
    }

    @Test
    void shouldRejectWhenObservedVersionIsBehindDesired() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 3L, 2L, true, 0L, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.VERSION_NOT_VERIFIED));
        assertEquals(0, result.count(EvidenceRejectionReason.NO_BINDING));
    }

    @Test
    void shouldRejectWhenDocumentIsDisabled() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 1L, 1L, false, 0L, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
    }

    @Test
    void shouldRejectWhenDocumentIsSoftDeleted() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 1L, 1L, true, NOW, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
    }

    @Test
    void shouldRejectWhenDocumentIsSuperseded() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 1L, 1L, true, 0L, OTHER_DOC);

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
    }

    @Test
    void shouldRejectWhenBoundDocumentIsMissing() {
        Fixture fixture = Fixture.empty();
        fixture.bindings.save(inSyncBinding(DOC, SCOPE_KB, 1L, 1L));

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
        assertEquals(0, result.count(EvidenceRejectionReason.NO_BINDING));
    }

    @Test
    void shouldRejectWhenDocumentKnowledgeBaseIsOutsideScopeEvenIfInSync() {
        Fixture fixture = Fixture.empty();
        fixture.seed(OTHER_DOC, OTHER_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 4L, 4L, true, 0L, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(OTHER_KB, OTHER_DOC))),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.OUT_OF_SCOPE));
        assertEquals(0, result.count(EvidenceRejectionReason.NO_BINDING));
        assertEquals(0, result.count(EvidenceRejectionReason.VERSION_NOT_VERIFIED));
        assertEquals(0, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
    }

    @Test
    void shouldAdmitADerivedChildPathWhenTheDocumentRootBindingIsVerified() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 2L, 2L, true, 0L, "");
        String child = OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC) + "/.abstract.md";

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(List.of(hit(child)), Set.of(SCOPE_KB));

        assertEquals(1, result.admitted().size());
        AdmittedKnowledgeEvidence admitted = result.admitted().getFirst();
        assertEquals(child, admitted.hit().uri());
        assertEquals(DOC, admitted.binding().documentId());
        assertEquals(DOC, admitted.document().id());
        assertEquals(0, result.rejectedCount());
    }

    @Test
    void rejectionTallyIsCompleteAndPerReason() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 2L, 1L, true, 0L, "");
        fixture.seed(OTHER_DOC, OTHER_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 1L, 1L, true, 0L, "");
        String missing = OpenVikingProjectionUris.documentRootUri(SCOPE_KB, "7493631836980121600");
        String disabledId = "7493631838477488128";
        fixture.seed(disabledId, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 1L, 1L, false, 0L, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(
                        hit(missing),
                        hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC)),
                        hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, disabledId)),
                        hit(OpenVikingProjectionUris.documentRootUri(OTHER_KB, OTHER_DOC))
                ),
                Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(1, result.count(EvidenceRejectionReason.NO_BINDING));
        assertEquals(1, result.count(EvidenceRejectionReason.VERSION_NOT_VERIFIED));
        assertEquals(1, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
        assertEquals(1, result.count(EvidenceRejectionReason.OUT_OF_SCOPE));
        assertEquals(4, result.rejectedCount());
        assertEquals(4, result.rejectionCounts().size());
    }

    @Test
    void emptyHitsAreObservableAsNoRecallRatherThanRejections() {
        Fixture fixture = Fixture.empty();
        fixture.seed(DOC, SCOPE_KB, ExternalKnowledgeProjectionStatus.IN_SYNC, 1L, 1L, true, 0L, "");

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(List.of(), Set.of(SCOPE_KB));

        assertTrue(result.admitted().isEmpty());
        assertEquals(0, result.rejectedCount());
        for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
            assertEquals(0, result.count(reason));
        }
    }

    @Test
    void visibleHelperIsNotSufficientBecauseEnabledIsASeparateField() {
        KnowledgeDocument disabled = activeDocument(DOC, SCOPE_KB).withEnabled(false);
        assertTrue(disabled.visible(), "visible() only covers tombstone/supersede; allowlist must also require enabled");

        Fixture fixture = Fixture.empty();
        fixture.documents.save(disabled, "body");
        fixture.bindings.save(inSyncBinding(DOC, SCOPE_KB, 1L, 1L));

        KnowledgeEvidenceAllowlistResult result = fixture.allowlist.admit(
                List.of(hit(OpenVikingProjectionUris.documentRootUri(SCOPE_KB, DOC))),
                Set.of(SCOPE_KB));

        assertEquals(1, result.count(EvidenceRejectionReason.DOCUMENT_NOT_ACTIVE));
    }

    private static ExternalNavigatorSearch.Hit hit(String uri) {
        return new ExternalNavigatorSearch.Hit(uri, 0, 0.9, "abstract", List.of());
    }

    private static KnowledgeDocument activeDocument(String id, String knowledgeBaseId) {
        return new KnowledgeDocument(
                id,
                knowledgeBaseId,
                "doc-" + id + ".md",
                "api",
                "text/markdown",
                KnowledgeDocumentStatus.INDEXED,
                true,
                1,
                List.of(),
                NOW,
                "LOCAL",
                "",
                "",
                "",
                "ck-" + id,
                "preview",
                NOW,
                0L,
                1L,
                "",
                "",
                0L,
                0L,
                "",
                0L,
                false
        );
    }

    private static KnowledgeExternalIndexBinding inSyncBinding(
            String documentId,
            String knowledgeBaseId,
            long desiredVersion,
            long observedVersion
    ) {
        return new KnowledgeExternalIndexBinding(
                KnowledgeExternalIndexBinding.OPENVIKING,
                documentId,
                knowledgeBaseId,
                OpenVikingProjectionUris.documentRootUri(knowledgeBaseId, documentId),
                OpenVikingProjectionUris.ownershipMarker(knowledgeBaseId, documentId),
                ExternalKnowledgeDesiredState.PRESENT,
                desiredVersion,
                "ck",
                ExternalKnowledgeObservedState.READY,
                observedVersion,
                "ck",
                ExternalKnowledgeProjectionStatus.IN_SYNC,
                "",
                "",
                "",
                NOW,
                NOW,
                "",
                "",
                0L,
                NOW,
                NOW
        );
    }

    private static final class Fixture {
        final InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        final KnowledgeEvidenceAllowlist allowlist = new KnowledgeEvidenceAllowlist(bindings, documents);

        static Fixture empty() {
            return new Fixture();
        }

        void seed(
                String documentId,
                String knowledgeBaseId,
                ExternalKnowledgeProjectionStatus status,
                long desiredVersion,
                long observedVersion,
                boolean enabled,
                long deletedAt,
                String supersededBy
        ) {
            KnowledgeDocument document = new KnowledgeDocument(
                    documentId,
                    knowledgeBaseId,
                    "doc-" + documentId + ".md",
                    "api",
                    "text/markdown",
                    KnowledgeDocumentStatus.INDEXED,
                    enabled,
                    1,
                    List.of(),
                    NOW,
                    "LOCAL",
                    "",
                    "",
                    "",
                    "ck",
                    "preview",
                    NOW,
                    0L,
                    1L,
                    "",
                    "",
                    deletedAt,
                    deletedAt == 0L ? 0L : deletedAt + 1L,
                    supersededBy,
                    0L,
                    false
            );
            documents.save(document, "body");
            bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    documentId,
                    knowledgeBaseId,
                    OpenVikingProjectionUris.documentRootUri(knowledgeBaseId, documentId),
                    OpenVikingProjectionUris.ownershipMarker(knowledgeBaseId, documentId),
                    ExternalKnowledgeDesiredState.PRESENT,
                    desiredVersion,
                    "ck",
                    ExternalKnowledgeObservedState.READY,
                    observedVersion,
                    "ck",
                    status,
                    "",
                    "",
                    "",
                    NOW,
                    NOW,
                    "",
                    "",
                    0L,
                    NOW,
                    NOW
            ));
        }
    }
}
