package com.wish.rd.bootstrap.controller.admin.knowledge;

import com.wish.rd.bootstrap.openviking.impl.DisabledExternalKnowledgeIndexPort;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.KnowledgeDocumentMutationEngine;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.ExternalIndexIdempotencyKeys;
import com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexReconcileEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeInventoryAuditEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionAdminEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionBackfillEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionWakePort;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeInventoryAuditStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeReconcileFindingStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeRemoval;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillSettings;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投影管理 API：只组装 Engine 结果，错误体不含堆栈与原始远端响应。
 */
class KnowledgeProjectionAdminControllerTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String CHECKSUM = "a".repeat(64);
    private static final String ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";
    private static final String OWNED = "viking://resources/rd-bot/kb/1001/";

    @Test
    void shouldWrapEveryEndpointInADataEnvelope() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveOwnedDocument();
        fixture.saveInSyncBinding();
        fixture.enqueueDeadLetter("8101", 4L);
        fixture.saveTombstone("2002");

        MockMvc mvc = fixture.mvc();

        mvc.perform(get("/admin/knowledge-base/1001/openviking/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.bindingCounts.IN_SYNC").value(1))
                .andExpect(jsonPath("$.data.unconvergedCount").exists());

        mvc.perform(get("/admin/knowledge-base/1001/openviking/documents").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].documentId").value("2001"))
                .andExpect(jsonPath("$.data.records[0].projectionStatus").value("IN_SYNC"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1));

        mvc.perform(get("/admin/knowledge-base/1001/openviking/documents/2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.binding.documentId").value("2001"))
                .andExpect(jsonPath("$.data.operations").isArray())
                .andExpect(jsonPath("$.data.lastErrorMessage").exists());

        mvc.perform(get("/admin/knowledge-base/1001/openviking/tree").param("uri", OWNED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].uri").value(ROOT))
                .andExpect(jsonPath("$.data.entries[0].directory").value(true));

        mvc.perform(get("/admin/knowledge-base/1001/openviking/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.semanticConfigFingerprint").value("fp-default"))
                .andExpect(jsonPath("$.data.openFindingCount").value(0));

        mvc.perform(get("/admin/knowledge-base/1001/openviking/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].eventId").value("8101"))
                .andExpect(jsonPath("$.data.records[0].status").value("DEAD_LETTER"))
                .andExpect(jsonPath("$.data.records[0].rowVersion").value(4));

        mvc.perform(get("/admin/knowledge-base/1001/openviking/tombstones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].documentId").value("2002"))
                .andExpect(jsonPath("$.data.records[0].tombstone").doesNotExist());

        mvc.perform(post("/admin/knowledge-base/1001/openviking/documents/2001/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("NOTHING_TO_RETRY"));

        mvc.perform(post("/admin/knowledge-base/1001/openviking/documents/2001/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("VERIFIED"));

        mvc.perform(post("/admin/knowledge-base/1001/openviking/documents/2001/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REBUILT"));

        mvc.perform(post("/admin/knowledge-base/1001/openviking/dead-letters/8101/requeue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRowVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REQUEUED"))
                .andExpect(jsonPath("$.data.operationStatus").value("PENDING"));

        mvc.perform(post("/admin/knowledge-base/1001/openviking/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings").isArray())
                .andExpect(jsonPath("$.data.counts").exists());
    }

    @Test
    void shouldRejectDocumentsThatDoNotBelongToTheKnowledgeBase() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveOwnedDocument();
        fixture.saveInSyncBinding();
        fixture.saveForeignDocument();

        MvcResult result = fixture.mvc()
                .perform(post("/admin/knowledge-base/1001/openviking/documents/3001/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andReturn();
        assertNoInternalLeak(result.getResponse().getContentAsString());
    }

    @Test
    void shouldConflictWhenRequeueRowVersionIsStale() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveOwnedDocument();
        fixture.saveInSyncBinding();
        fixture.enqueueDeadLetter("8101", 4L);

        fixture.mvc()
                .perform(post("/admin/knowledge-base/1001/openviking/dead-letters/8101/requeue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRowVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("row_version")));
    }

    @Test
    void shouldRejectTreeUriOutsideOwnedRoot() throws Exception {
        Fixture fixture = Fixture.ready();

        MvcResult result = fixture.mvc()
                .perform(get("/admin/knowledge-base/1001/openviking/tree")
                        .param("uri", "viking://resources/rd-bot/kb/9999/"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertNoInternalLeak(body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("viking://"),
                "the owned-root URI itself may appear in the error");
        org.junit.jupiter.api.Assertions.assertTrue(fixture.port.listed.isEmpty());
    }

    @Test
    void shouldKeepReadsWorkingWhenProjectionIsDisabled() throws Exception {
        Fixture fixture = Fixture.disabled();
        fixture.saveOwnedDocument();
        fixture.saveInSyncBinding();

        MockMvc mvc = fixture.mvc();
        mvc.perform(get("/admin/knowledge-base/1001/openviking/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.bindingCounts.IN_SYNC").value(1));

        mvc.perform(post("/admin/knowledge-base/1001/openviking/documents/2001/verify"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("关闭")));

        mvc.perform(post("/admin/knowledge-base/1001/openviking/reconcile"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("关闭")));
    }

    @Test
    void shouldWrapInventoryOverviewInADataEnvelopeWithSumCheck() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.savePendingCandidate("2101");
        fixture.saveInSyncDocument("2401");
        fixture.saveTombstone("2002");

        fixture.mvc()
                .perform(get("/admin/knowledge-base/1001/openviking/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.PENDING_BACKFILL").value(1))
                .andExpect(jsonPath("$.data.categories.TOMBSTONE").value(1))
                .andExpect(jsonPath("$.data.categories.IN_SYNC").value(1))
                .andExpect(jsonPath("$.data.documentTotal").value(3))
                .andExpect(jsonPath("$.data.sumMatchesTotal").value(true))
                .andExpect(jsonPath("$.data.pendingBackfillRemaining").value(1))
                .andExpect(jsonPath("$.data.inFlightOperations").value(0));
    }

    @Test
    void shouldKeysetPaginateInventoryCandidates() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.savePendingCandidate("2101");
        fixture.savePendingCandidate("2102");
        fixture.savePendingCandidate("2103");

        MockMvc mvc = fixture.mvc();
        mvc.perform(get("/admin/knowledge-base/1001/openviking/inventory/candidates")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].documentId").value("2101"))
                .andExpect(jsonPath("$.data.records[1].documentId").value("2102"))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.after").value("2102"));

        mvc.perform(get("/admin/knowledge-base/1001/openviking/inventory/candidates")
                        .param("after", "2102")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].documentId").value("2103"))
                .andExpect(jsonPath("$.data.after").value(""));
    }

    @Test
    void shouldListDuplicateGroupsWithProposedSurvivor() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.saveDuplicatePair();

        fixture.mvc()
                .perform(get("/admin/knowledge-base/1001/openviking/inventory/duplicates").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].identityKey").value(Fixture.DUPLICATE_IDENTITY))
                .andExpect(jsonPath("$.data.records[0].proposedSurvivorDocumentId").value("2202"))
                .andExpect(jsonPath("$.data.records[0].members.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].members[0].rowVersion").exists());
    }

    @Test
    void shouldListLocalOrphanDrift() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.saveTombstone("2301");
        fixture.savePresentBinding("2301");

        fixture.mvc()
                .perform(get("/admin/knowledge-base/1001/openviking/inventory/drift").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].documentId").value("2301"))
                .andExpect(jsonPath("$.data.records[0].desiredState").value("PRESENT"));
    }

    @Test
    void shouldRunBoundedBackfillAndReturnPerDocumentOutcomes() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.savePendingCandidate("2101");
        fixture.savePendingCandidate("2102");

        fixture.mvc()
                .perform(post("/admin/knowledge-base/1001/openviking/inventory/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempted").value(1))
                .andExpect(jsonPath("$.data.applied").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.stopReason").value(""))
                .andExpect(jsonPath("$.data.outcomes[0].documentId").value("2101"))
                .andExpect(jsonPath("$.data.outcomes[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.data.outcomes[0].reason").exists());
    }

    @Test
    void shouldReturnRestrictionReasonWhenInFlightCapBlocksBackfill() throws Exception {
        Fixture fixture = Fixture.capped(1);
        fixture.saveActiveBase();
        fixture.savePendingCandidate("2101");
        fixture.enqueuePendingOutbox("cap-1", "2099");

        fixture.mvc()
                .perform(post("/admin/knowledge-base/1001/openviking/inventory/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempted").value(0))
                .andExpect(jsonPath("$.data.applied").value(0))
                .andExpect(jsonPath("$.data.stopReason").value(containsString("in-flight")))
                .andExpect(jsonPath("$.data.outcomes").isArray());
    }

    @Test
    void shouldConflictWhenResolveRowVersionIsStale() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.saveDuplicatePair();

        fixture.mvc()
                .perform(post("/admin/knowledge-base/1001/openviking/inventory/duplicates/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identityKey":"%s","survivorDocumentId":"2202",
                                 "losers":[{"documentId":"2201","expectedRowVersion":99}]}
                                """.formatted(Fixture.DUPLICATE_IDENTITY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("row_version")))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void shouldRejectResolveBodyMissingExpectedRowVersion() throws Exception {
        Fixture fixture = Fixture.ready();
        fixture.saveActiveBase();
        fixture.saveDuplicatePair();

        MvcResult result = fixture.mvc()
                .perform(post("/admin/knowledge-base/1001/openviking/inventory/duplicates/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identityKey":"%s","survivorDocumentId":"2202",
                                 "losers":[{"documentId":"2201"}]}
                                """.formatted(Fixture.DUPLICATE_IDENTITY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("expectedRowVersion")))
                .andReturn();
        assertNoInternalLeak(result.getResponse().getContentAsString());
    }

    @Test
    void shouldKeepInventoryReadsWorkingWhenProjectionIsDisabled() throws Exception {
        Fixture fixture = Fixture.disabled();
        fixture.saveActiveBase();
        fixture.savePendingCandidate("2101");

        fixture.mvc()
                .perform(get("/admin/knowledge-base/1001/openviking/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingBackfillRemaining").value(1))
                .andExpect(jsonPath("$.data.sumMatchesTotal").value(true));
    }

    private static void assertNoInternalLeak(String body) {
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("at com.wish"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Exception"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("OpenVikingHttpExchange"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("X-API-Key"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("apiKey"), body);
        org.junit.jupiter.api.Assertions.assertFalse(
                body.contains("bootstrap/src") || body.contains("java.lang."), body);
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("stackTrace")));
    }

    private static final class RecordingIndexPort implements ExternalKnowledgeIndexPort {

        private final List<String> listed = new ArrayList<>();

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
            throw new AssertionError("controller must not submit writes");
        }

        @Override
        public ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId) {
            throw new AssertionError("controller must not poll tasks");
        }

        @Override
        public ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker) {
            return ExternalKnowledgeVerification.passed();
        }

        @Override
        public ExternalKnowledgeRemoval removeResource(String remoteUri, boolean recursive, String expectedOwnedRoot) {
            throw new AssertionError("controller must not delete remotely");
        }

        @Override
        public ExternalResourceProbe inspectResource(String remoteUri) {
            return ExternalResourceProbe.absent();
        }

        @Override
        public ExternalTreeListing listTree(String ownedRootUri) {
            listed.add(ownedRootUri);
            return ExternalTreeListing.of(List.of(
                    new ExternalTreeListing.Entry(ROOT, "2001", true, "rd-bot")));
        }
    }

    private static final class Fixture {

        static final String DUPLICATE_IDENTITY = "FEISHU:tok-dup";

        private final InMemoryKnowledgeExternalIndexOutboxStore outbox =
                new InMemoryKnowledgeExternalIndexOutboxStore();
        private final InMemoryKnowledgeExternalIndexBindingStore bindings =
                new InMemoryKnowledgeExternalIndexBindingStore();
        private final InMemoryKnowledgeReconcileFindingStore findings =
                new InMemoryKnowledgeReconcileFindingStore();
        private final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        private final InMemoryKnowledgeBaseStore bases = new InMemoryKnowledgeBaseStore();
        private final InMemoryKnowledgeDocumentRevisionStore revisions = new InMemoryKnowledgeDocumentRevisionStore();
        private final InMemoryKnowledgeChunkStore chunks = new InMemoryKnowledgeChunkStore();
        private final InMemoryVectorStore vectors = new InMemoryVectorStore();
        private final ExternalKnowledgeIndexPort indexPort;
        private final RecordingIndexPort port;
        private final AtomicLong ids = new AtomicLong(9000L);
        private final KnowledgeProjectionAdminEngine engine;

        private Fixture(
                ExternalKnowledgeIndexPort indexPort,
                RecordingIndexPort recording,
                InventoryBackfillSettings backfillSettings
        ) {
            this.indexPort = indexPort;
            this.port = recording;
            KnowledgeExternalIndexReconcileEngine reconciler = new KnowledgeExternalIndexReconcileEngine(
                    indexPort,
                    bindings,
                    outbox,
                    findings,
                    ProjectionWorkerSettings.defaults(),
                    0L,
                    () -> Long.toString(ids.getAndIncrement()));
            InMemoryKnowledgeMutationTransactionAdapter tx = new InMemoryKnowledgeMutationTransactionAdapter(
                    documents, revisions, chunks, vectors, bindings, outbox, bases);
            KnowledgeDocumentMutationEngine mutations = new KnowledgeDocumentMutationEngine(
                    SnowflakeIdGenerator.defaultGenerator(),
                    bases,
                    documents,
                    revisions,
                    chunks,
                    tx,
                    KnowledgeProjectionWakePort.noop(),
                    bindings);
            InMemoryKnowledgeInventoryAuditStore audit = new InMemoryKnowledgeInventoryAuditStore(
                    documents, bases, bindings, outbox);
            KnowledgeInventoryAuditEngine auditEngine = new KnowledgeInventoryAuditEngine(audit);
            KnowledgeProjectionBackfillEngine backfillEngine = new KnowledgeProjectionBackfillEngine(
                    audit, mutations, backfillSettings);
            this.engine = new KnowledgeProjectionAdminEngine(
                    indexPort,
                    bindings,
                    outbox,
                    findings,
                    documents,
                    reconciler,
                    ProjectionWorkerSettings.defaults(),
                    () -> Long.toString(ids.getAndIncrement()),
                    auditEngine,
                    backfillEngine,
                    mutations);
        }

        static Fixture ready() {
            RecordingIndexPort port = new RecordingIndexPort();
            return new Fixture(port, port, InventoryBackfillSettings.defaults());
        }

        static Fixture disabled() {
            return new Fixture(
                    new DisabledExternalKnowledgeIndexPort(),
                    new RecordingIndexPort(),
                    InventoryBackfillSettings.defaults());
        }

        static Fixture capped(int maxInFlight) {
            RecordingIndexPort port = new RecordingIndexPort();
            return new Fixture(port, port, new InventoryBackfillSettings(20, maxInFlight));
        }

        private MockMvc mvc() {
            return MockMvcBuilders.standaloneSetup(new KnowledgeProjectionAdminController(engine)).build();
        }

        private void saveActiveBase() {
            bases.save(new KnowledgeBase("1001", "kb-1001", "", true, NOW));
        }

        private void saveOwnedDocument() {
            documents.save(new KnowledgeDocument(
                    "2001", "1001", "doc-2001", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, 0, List.of(), NOW), "body");
        }

        private void saveForeignDocument() {
            documents.save(new KnowledgeDocument(
                    "3001", "1002", "foreign", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, 0, List.of(), NOW), "body");
        }

        private void saveTombstone(String documentId) {
            KnowledgeDocument live = new KnowledgeDocument(
                    documentId, "1001", "gone.md", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, false, 0, List.of(), NOW);
            documents.save(live.withSoftDeleted(NOW, NOW + 86_400_000L), "body");
        }

        private void savePendingCandidate(String documentId) {
            documents.save(new KnowledgeDocument(
                    documentId, "1001", "pending-" + documentId + ".md", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, 1, List.of(), NOW,
                    "FEISHU", "tok-" + documentId, "https://example.feishu.cn/wiki/tok-" + documentId,
                    "", "checksum-" + documentId, "preview", NOW, 0L, 1L, "", "", 0L, 0L, "", 0L, false),
                    "body-" + documentId);
            chunks.save(new KnowledgeChunk(
                    "c-" + documentId, documentId, "1001", 0, "body-" + documentId, "document",
                    "pending-" + documentId + ".md", true, Map.of("documentId", documentId)));
            vectors.index(List.of(new RetrievedChunk(
                    "c-" + documentId, "body-" + documentId, "1001", "document",
                    "pending-" + documentId + ".md", 1.0d, Map.of("documentId", documentId))));
        }

        private void saveInSyncDocument(String documentId) {
            documents.save(new KnowledgeDocument(
                    documentId, "1001", "sync-" + documentId + ".md", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, 1, List.of(), NOW,
                    "FEISHU", "tok-" + documentId, "https://example.feishu.cn/wiki/tok-" + documentId,
                    "", CHECKSUM, "preview", NOW, 0L, 1L, "", "id-" + documentId, 0L, 0L, "", 0L, false),
                    "synced");
            savePresentBinding(documentId);
        }

        private void saveDuplicatePair() {
            documents.save(new KnowledgeDocument(
                    "2201", "1001", "dup-old.md", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, 1, List.of(), NOW - 20_000L,
                    "FEISHU", "tok-dup", "https://example.feishu.cn/wiki/tok-dup",
                    "", "ck-old", "preview", NOW - 20_000L, 0L, 1L, "", DUPLICATE_IDENTITY,
                    0L, 0L, "", 3L, false), "old");
            documents.save(new KnowledgeDocument(
                    "2202", "1001", "dup-new.md", "document", "text/markdown",
                    KnowledgeDocumentStatus.INDEXED, true, 1, List.of(), NOW - 10_000L,
                    "FEISHU", "tok-dup", "https://example.feishu.cn/wiki/tok-dup",
                    "", "ck-new", "preview", NOW, 0L, 1L, "", DUPLICATE_IDENTITY,
                    0L, 0L, "", 5L, false), "new");
        }

        private void saveInSyncBinding() {
            savePresentBinding("2001");
        }

        private void savePresentBinding(String documentId) {
            String uri = OpenVikingProjectionUris.documentRootUri("1001", documentId);
            bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, documentId, "1001", uri, "rd-bot:1001:" + documentId,
                    ExternalKnowledgeDesiredState.PRESENT, 1L, CHECKSUM,
                    ExternalKnowledgeObservedState.READY, 1L, CHECKSUM,
                    ExternalKnowledgeProjectionStatus.IN_SYNC, "", "", "fp-default",
                    NOW - 60_000L, NOW - 60_000L, "", "", 0L, NOW - 60_000L, NOW - 60_000L));
        }

        private void enqueuePendingOutbox(String eventId, String documentId) {
            outbox.enqueue(new KnowledgeExternalIndexOperation(
                    eventId,
                    ExternalIndexIdempotencyKeys.document(
                            KnowledgeExternalIndexBinding.OPENVIKING,
                            documentId,
                            1L,
                            ExternalKnowledgeOperationType.UPSERT_DOCUMENT),
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                    "1001",
                    documentId,
                    1L,
                    CHECKSUM,
                    OpenVikingProjectionUris.documentRootUri("1001", documentId),
                    "",
                    "",
                    ExternalKnowledgeOperationStatus.PENDING,
                    "",
                    "",
                    "",
                    0L,
                    0,
                    8,
                    NOW,
                    0L,
                    "",
                    "",
                    0L,
                    NOW,
                    NOW
            ));
        }

        private void enqueueDeadLetter(String eventId, long rowVersion) {
            outbox.enqueue(new KnowledgeExternalIndexOperation(
                    eventId,
                    ExternalIndexIdempotencyKeys.document(
                            KnowledgeExternalIndexBinding.OPENVIKING,
                            "2001",
                            1L,
                            ExternalKnowledgeOperationType.UPSERT_DOCUMENT),
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                    "1001",
                    "2001",
                    1L,
                    CHECKSUM,
                    ROOT,
                    "5001",
                    "",
                    ExternalKnowledgeOperationStatus.DEAD_LETTER,
                    "",
                    "",
                    "",
                    0L,
                    8,
                    8,
                    NOW,
                    0L,
                    "DEAD",
                    "exhausted",
                    rowVersion,
                    NOW,
                    NOW
            ));
        }
    }
}
