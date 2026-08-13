package com.wish.rd.bootstrap.controller.admin.knowledge;

import com.wish.rd.bootstrap.openviking.impl.DisabledExternalKnowledgeIndexPort;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.ExternalIndexIdempotencyKeys;
import com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexReconcileEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionAdminEngine;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
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
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
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

        private final InMemoryKnowledgeExternalIndexOutboxStore outbox =
                new InMemoryKnowledgeExternalIndexOutboxStore();
        private final InMemoryKnowledgeExternalIndexBindingStore bindings =
                new InMemoryKnowledgeExternalIndexBindingStore();
        private final InMemoryKnowledgeReconcileFindingStore findings =
                new InMemoryKnowledgeReconcileFindingStore();
        private final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        private final ExternalKnowledgeIndexPort indexPort;
        private final RecordingIndexPort port;
        private final AtomicLong ids = new AtomicLong(9000L);
        private final KnowledgeProjectionAdminEngine engine;

        private Fixture(ExternalKnowledgeIndexPort indexPort, RecordingIndexPort recording) {
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
            this.engine = new KnowledgeProjectionAdminEngine(
                    indexPort,
                    bindings,
                    outbox,
                    findings,
                    documents,
                    reconciler,
                    ProjectionWorkerSettings.defaults(),
                    () -> Long.toString(ids.getAndIncrement()));
        }

        static Fixture ready() {
            RecordingIndexPort port = new RecordingIndexPort();
            return new Fixture(port, port);
        }

        static Fixture disabled() {
            return new Fixture(new DisabledExternalKnowledgeIndexPort(), new RecordingIndexPort());
        }

        private MockMvc mvc() {
            return MockMvcBuilders.standaloneSetup(new KnowledgeProjectionAdminController(engine)).build();
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

        private void saveInSyncBinding() {
            bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "2001", "1001", ROOT, "rd-bot:1001:2001",
                    ExternalKnowledgeDesiredState.PRESENT, 1L, CHECKSUM,
                    ExternalKnowledgeObservedState.READY, 1L, CHECKSUM,
                    ExternalKnowledgeProjectionStatus.IN_SYNC, "", "", "fp-default",
                    NOW - 60_000L, NOW - 60_000L, "", "", 0L, NOW - 60_000L, NOW - 60_000L));
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
