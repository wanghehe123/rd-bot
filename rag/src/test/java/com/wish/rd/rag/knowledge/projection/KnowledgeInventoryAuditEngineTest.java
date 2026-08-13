package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeInventoryAuditStore;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.InventoryAuditReport;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeInventoryAuditEngineTest {

    private static final String KB = "101";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void overviewReportsSumMatchAndDoesNotCountDriftInCategories() {
        Fixture fixture = Fixture.seeded();
        InventoryAuditReport report = fixture.engine.overview(KB);

        assertEquals(3L, report.documentTotal());
        assertTrue(report.sumMatchesTotal());
        assertEquals(1L, report.categories().count(InventoryCategory.TOMBSTONE));
        assertEquals(1L, report.categories().count(InventoryCategory.PENDING_BACKFILL));
        assertEquals(1L, report.categories().count(InventoryCategory.IN_SYNC));
        assertEquals(1L, report.pendingBackfillRemaining());
        assertEquals(0L, report.inFlightOperations());
        assertEquals(1, fixture.engine.drift(KB, 10).size());
        assertEquals(1, fixture.engine.candidates(KB, "", 10).size());
    }

    @Test
    void overviewMarksSumMismatchWhenCountsDiverge() {
        Fixture fixture = Fixture.seeded();
        fixture.documents.save(doc("9", KB, 1, "ck", "", 0L, false), "body");
        InventoryAuditReport report = fixture.engine.overview(KB);
        assertEquals(fixture.audit.countDocuments(KB), report.categories().sum());
        assertTrue(report.sumMatchesTotal());
        assertFalse(report.categories().sum() == 0L);
    }

    private static KnowledgeDocument doc(
            String id,
            String kb,
            int chunks,
            String checksum,
            String identity,
            long deletedAt,
            boolean localOnly
    ) {
        return new KnowledgeDocument(
                id, kb, id + ".md", "api", "text/markdown", KnowledgeDocumentStatus.INDEXED, true,
                chunks, List.of(), NOW, "LOCAL", "", "", "", checksum, "preview", NOW, 0L,
                1L, "", identity, deletedAt, 0L, "", 0L, localOnly);
    }

    private static final class Fixture {
        final InMemoryKnowledgeBaseStore bases = new InMemoryKnowledgeBaseStore();
        final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        final InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        final InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
        final KnowledgeInventoryAuditStore audit;
        final KnowledgeInventoryAuditEngine engine;

        private Fixture() {
            this.audit = new InMemoryKnowledgeInventoryAuditStore(documents, bases, bindings, outbox);
            this.engine = new KnowledgeInventoryAuditEngine(audit);
        }

        static Fixture seeded() {
            Fixture fixture = new Fixture();
            fixture.bases.save(new KnowledgeBase(KB, "kb", "", true, NOW));
            fixture.documents.save(doc("1", KB, 1, "ck-tomb", "id-tomb", NOW, false), "body");
            fixture.bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "1", KB, "uri-1", "owner",
                    ExternalKnowledgeDesiredState.PRESENT, 1L, "ck-tomb",
                    ExternalKnowledgeObservedState.READY, 1L, "ck-tomb",
                    ExternalKnowledgeProjectionStatus.IN_SYNC, "", "", "", 0L, 0L, "", "", 1L, NOW, NOW));
            fixture.documents.save(doc("2", KB, 1, "ck-sync", "id-sync", 0L, false), "body");
            fixture.bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, "2", KB, "uri-2", "owner",
                    ExternalKnowledgeDesiredState.PRESENT, 1L, "ck-sync",
                    ExternalKnowledgeObservedState.READY, 1L, "ck-sync",
                    ExternalKnowledgeProjectionStatus.IN_SYNC, "", "", "", 0L, 0L, "", "", 1L, NOW, NOW));
            fixture.documents.save(doc("3", KB, 1, "ck-pending", "", 0L, false), "body");
            return fixture;
        }
    }
}
