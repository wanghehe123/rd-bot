package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeBaseLifecycle;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeInventoryAuditStore;
import com.wish.rd.rag.knowledge.projection.model.DuplicateIdentityGroup;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategory;
import com.wish.rd.rag.knowledge.projection.model.InventoryCategoryCounts;
import com.wish.rd.rag.knowledge.projection.model.InventoryDriftEntry;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存审计实现的行为契约，并核对 Postgres Mapper SQL 使用同一套 D5 列名与判定顺序。
 */
class KnowledgeInventoryAuditStoreContractTest {

    private static final String KB = "101";
    private static final String INACTIVE_KB = "202";
    private static final String DUP_IDENTITY = "feishu-token-dup";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void classificationIsExhaustiveAndHonorsD5OrderOnOverlaps() {
        Fixture fixture = Fixture.seeded();
        InventoryCategoryCounts counts = fixture.audit.countByCategory(KB);
        long total = fixture.audit.countDocuments(KB);

        assertEquals(total, counts.sum(), "every document must land in exactly one category");
        assertEquals(1L, counts.count(InventoryCategory.TOMBSTONE));
        assertEquals(1L, counts.count(InventoryCategory.SUPERSEDED));
        assertEquals(3L, counts.count(InventoryCategory.DUPLICATE_UNRESOLVED));
        assertEquals(0L, counts.count(InventoryCategory.EXCLUDED_BASE_INACTIVE));
        assertEquals(1L, counts.count(InventoryCategory.EXCLUDED_LOCAL_ONLY));
        assertEquals(1L, counts.count(InventoryCategory.EXCLUDED_EMPTY));
        assertEquals(1L, counts.count(InventoryCategory.FAILED));
        assertEquals(1L, counts.count(InventoryCategory.IN_SYNC));
        assertEquals(1L, counts.count(InventoryCategory.PROJECTING));
        assertEquals(2L, counts.count(InventoryCategory.PENDING_BACKFILL));
        assertEquals(12L, total);

        InventoryCategoryCounts inactive = fixture.audit.countByCategory(INACTIVE_KB);
        assertEquals(fixture.audit.countDocuments(INACTIVE_KB), inactive.sum());
        assertEquals(1L, inactive.count(InventoryCategory.TOMBSTONE),
                "tombstones stay tombstones even when the base is inactive");
        assertEquals(1L, inactive.count(InventoryCategory.EXCLUDED_BASE_INACTIVE),
                "a local-only doc on an inactive base is excluded by base status, not local-only");
        assertEquals(0L, inactive.count(InventoryCategory.EXCLUDED_LOCAL_ONLY));

        EnumSet<InventoryCategory> seen = EnumSet.noneOf(InventoryCategory.class);
        for (InventoryCategory category : InventoryCategory.values()) {
            if (counts.count(category) > 0 || inactive.count(category) > 0) {
                seen.add(category);
            }
        }
        assertEquals(EnumSet.allOf(InventoryCategory.class), seen);
    }

    @Test
    void driftIsSeparateFromCategorySum() {
        Fixture fixture = Fixture.seeded();
        InventoryCategoryCounts counts = fixture.audit.countByCategory(KB);
        assertEquals(fixture.audit.countDocuments(KB), counts.sum());

        List<InventoryDriftEntry> drift = fixture.audit.listLocalOrphanDrift(KB, 10);
        assertEquals(1, drift.size());
        assertEquals("1", drift.getFirst().documentId());
        assertEquals(ExternalKnowledgeDesiredState.PRESENT, drift.getFirst().desiredState());
    }

    @Test
    void backfillCandidatesAreEligibleUnboundAndKeysetPaginated() {
        Fixture fixture = Fixture.seeded();
        List<KnowledgeDocument> first = fixture.audit.nextBackfillCandidates(KB, "", 1);
        assertEquals(1, first.size());
        assertEquals("10", first.getFirst().id());

        List<KnowledgeDocument> second = fixture.audit.nextBackfillCandidates(KB, first.getFirst().id(), 10);
        assertEquals(1, second.size());
        assertEquals("11", second.getFirst().id());

        List<KnowledgeDocument> all = fixture.audit.nextBackfillCandidates(KB, "", 20);
        assertEquals(List.of("10", "11"), all.stream().map(KnowledgeDocument::id).toList());
        assertTrue(all.stream().noneMatch(document -> "6".equals(document.id())),
                "local-only documents must not enter the backfill queue");
        assertTrue(all.stream().noneMatch(document -> "4".equals(document.id())
                        || "5".equals(document.id())
                        || "14".equals(document.id())),
                "unresolved duplicates must not enter the backfill queue");
    }

    @Test
    void duplicateGroupsProposeSurvivorBySyncThenCreatedThenId() {
        Fixture fixture = Fixture.seeded();
        List<DuplicateIdentityGroup> groups = fixture.audit.listDuplicateGroups(KB, 10);
        assertEquals(1, groups.size());
        DuplicateIdentityGroup group = groups.getFirst();
        assertEquals(DUP_IDENTITY, group.identityKey());
        assertEquals("14", group.proposedSurvivorDocumentId(),
                "last_synced_at DESC, then created_at DESC, then id DESC");
        assertEquals(List.of("14", "5", "4"), group.members().stream().map(member -> member.documentId()).toList());
    }

    @Test
    void postgresMapperSqlUsesTheSameColumnsAndD5Order() throws Exception {
        Path repoRoot = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(repoRoot.resolve("bootstrap"))) {
            repoRoot = repoRoot.getParent();
        }
        String mapper = Files.readString(repoRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeInventoryAuditMapper.java"));
        String p0 = Files.readString(repoRoot.resolve(
                "bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));
        String p11 = Files.readString(repoRoot.resolve(
                "bootstrap/src/main/resources/sql/postgres/p11_openviking_projection.sql"));

        int tombstone = mapper.indexOf("deleted_at IS NOT NULL");
        int superseded = mapper.indexOf("superseded_by_document_id IS NOT NULL");
        int duplicate = mapper.indexOf("DUPLICATE_UNRESOLVED");
        int inactive = mapper.indexOf("lifecycle_status");
        int localOnly = mapper.indexOf("local_only_override");
        int empty = mapper.indexOf("chunk_count");
        int failed = mapper.indexOf("FAILED");
        int inSync = mapper.indexOf("'IN_SYNC'");
        int projecting = mapper.indexOf("PROJECTING");
        int pending = mapper.indexOf("PENDING_BACKFILL");
        assertTrue(tombstone > 0 && superseded > tombstone && duplicate > superseded,
                "D5 order must start tombstone, superseded, duplicate");
        assertTrue(inactive > duplicate && localOnly > inactive && empty > localOnly,
                "D5 exclusions must follow duplicates");
        assertTrue(failed > empty && inSync > failed && projecting > inSync && pending > projecting,
                "D5 binding states must follow exclusions and end with pending backfill");

        for (String column : List.of(
                "deleted_at",
                "superseded_by_document_id",
                "source_identity_key",
                "local_only_override",
                "chunk_count",
                "checksum",
                "lifecycle_status",
                "projection_status",
                "desired_state",
                "last_synced_at",
                "created_at"
        )) {
            assertTrue(mapper.contains(column), "audit SQL must read " + column);
            assertTrue(
                    p0.contains(column) || p11.contains(column),
                    column + " must exist in p0 or p11 or the audit silently mis-counts"
            );
        }
        assertTrue(mapper.contains("ON CONFLICT (provider, document_id) DO NOTHING")
                || mapper.contains("b.document_id IS NULL")
                || mapper.contains("LEFT JOIN knowledge_external_index_bindings"),
                "candidates must require the absence of a binding");
        assertFalse(mapper.contains("JdbcTemplate"));
    }

    private static final class Fixture {
        final InMemoryKnowledgeBaseStore bases = new InMemoryKnowledgeBaseStore();
        final InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
        final InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        final InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
        final KnowledgeInventoryAuditStore audit = new InMemoryKnowledgeInventoryAuditStore(
                documents, bases, bindings, outbox);

        static Fixture seeded() {
            Fixture fixture = new Fixture();
            fixture.bases.save(new KnowledgeBase(KB, "active", "", true, NOW));
            fixture.bases.save(new KnowledgeBase(
                    INACTIVE_KB, "inactive", "", true, NOW, KnowledgeBaseLifecycle.DELETING, NOW, NOW + 1, 1L, 0L));

            fixture.documents.save(doc("1", KB, "tomb", 2, "ck-tomb", "id-tomb", NOW, NOW, 0L, "2", false)
                    .withSoftDeleted(NOW, NOW + 1), "body");
            fixture.bindings.save(binding("1", KB, ExternalKnowledgeDesiredState.PRESENT,
                    ExternalKnowledgeProjectionStatus.IN_SYNC, ExternalKnowledgeObservedState.READY, 3L));

            fixture.documents.save(doc("2", KB, "superseded", 1, "ck-sup", "id-sup", NOW, NOW, 0L, "3", false),
                    "body");

            fixture.documents.save(doc("3", KB, "other", 1, "ck-other", "id-other", NOW, NOW, 0L, "", false), "body");
            fixture.bindings.save(binding("3", KB, ExternalKnowledgeDesiredState.PRESENT,
                    ExternalKnowledgeProjectionStatus.PENDING, ExternalKnowledgeObservedState.UNKNOWN, 0L));

            fixture.documents.save(doc("4", KB, "dup-older", 1, "ck-dup-a", DUP_IDENTITY, NOW - 20, NOW - 20, 0L, "", false),
                    "body");
            fixture.documents.save(doc("5", KB, "dup-mid", 1, "ck-dup-b", DUP_IDENTITY, NOW - 5, NOW - 10, 0L, "", false),
                    "body");
            fixture.documents.save(doc("14", KB, "dup-newest", 1, "ck-dup-c", DUP_IDENTITY, NOW - 1, NOW - 2, 0L, "", false),
                    "body");

            fixture.documents.save(doc("6", KB, "local-only", 1, "ck-local", "id-local", NOW, NOW, 0L, "", true), "body");
            fixture.documents.save(doc("7", KB, "empty", 0, "", "id-empty", NOW, NOW, 0L, "", false), "");

            fixture.documents.save(doc("8", KB, "failed", 1, "ck-fail", "id-fail", NOW, NOW, 0L, "", false), "body");
            fixture.bindings.save(binding("8", KB, ExternalKnowledgeDesiredState.PRESENT,
                    ExternalKnowledgeProjectionStatus.FAILED, ExternalKnowledgeObservedState.UNKNOWN, 0L));

            fixture.documents.save(doc("9", KB, "synced", 1, "ck-sync", "id-sync", NOW, NOW, 0L, "", false), "body");
            fixture.bindings.save(binding("9", KB, ExternalKnowledgeDesiredState.PRESENT,
                    ExternalKnowledgeProjectionStatus.IN_SYNC, ExternalKnowledgeObservedState.READY, 4L));

            fixture.documents.save(doc("10", KB, "pending-a", 1, "ck-p1", "", NOW, NOW, 0L, "", false), "body");
            fixture.documents.save(doc("11", KB, "pending-b", 2, "ck-p2", "", NOW, NOW, 0L, "", false), "body");

            fixture.documents.save(doc("12", INACTIVE_KB, "inactive-tomb", 1, "ck-it", "id-it", NOW, NOW, 0L, "", false)
                    .withSoftDeleted(NOW, NOW + 1), "body");
            fixture.documents.save(doc("13", INACTIVE_KB, "inactive-local", 1, "ck-il", "id-il", NOW, NOW, 0L, "", true),
                    "body");
            return fixture;
        }

        private static KnowledgeDocument doc(
                String id,
                String kb,
                String name,
                int chunks,
                String checksum,
                String identity,
                long lastSynced,
                long created,
                long rowVersion,
                String supersededBy,
                boolean localOnly
        ) {
            return new KnowledgeDocument(
                    id,
                    kb,
                    name + ".md",
                    "api",
                    "text/markdown",
                    KnowledgeDocumentStatus.INDEXED,
                    true,
                    chunks,
                    List.of(),
                    created,
                    "LOCAL",
                    "",
                    "",
                    "",
                    checksum,
                    "preview",
                    lastSynced,
                    0L,
                    1L,
                    "",
                    identity,
                    0L,
                    0L,
                    supersededBy,
                    rowVersion,
                    localOnly
            );
        }

        private static KnowledgeExternalIndexBinding binding(
                String documentId,
                String kb,
                ExternalKnowledgeDesiredState desired,
                ExternalKnowledgeProjectionStatus status,
                ExternalKnowledgeObservedState observed,
                long observedVersion
        ) {
            return new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    documentId,
                    kb,
                    "viking://resources/rd-bot/kb/" + kb + "/documents/" + documentId,
                    "owner",
                    desired,
                    1L,
                    "ck",
                    observed,
                    observedVersion,
                    observedVersion > 0L ? "ck" : "",
                    status,
                    "",
                    "",
                    "",
                    0L,
                    0L,
                    "",
                    "",
                    3L,
                    NOW,
                    NOW
            );
        }
    }
}
