package com.wish.rd.rag.knowledge;

import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocImportCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocumentSnapshot;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeBaseLifecycle;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;
import com.wish.rd.rag.knowledge.projection.KnowledgeMutationTransactionPort;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillCommitResult;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeBundle;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentIdentityMutationTest {

    private static final String FEISHU_URL = "https://my.feishu.cn/wiki/Wp1IdentityToken";

    @Test
    void shouldKeepOneActiveDocumentIdAcrossThreeFeishuRevisions() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-1"));
        AtomicReference<FeishuDocumentSnapshot> snapshot = new AtomicReference<>(snapshot("1", "first body RD_WP1_V1"));
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace.mutations(), source -> snapshot.get());

        KnowledgeDocument first = importDoc(importer, base.id());
        snapshot.set(snapshot("2", "second body RD_WP1_V2"));
        KnowledgeDocument second = importDoc(importer, base.id());
        snapshot.set(snapshot("3", "third body RD_WP1_V3"));
        KnowledgeDocument third = importDoc(importer, base.id());

        assertEquals(first.id(), second.id());
        assertEquals(first.id(), third.id());
        assertEquals(1, workspace.listDocuments(base.id()).size());
        assertEquals(3L, third.syncVersion());
        List<KnowledgeDocumentRevision> revisions = workspace.listRevisions(first.id());
        assertEquals(3, revisions.size());
        assertEquals(revisions.get(2).id(), third.currentRevisionId());
        assertEquals("3", third.revisionId());
        assertTrue(workspace.previewDocument(first.id()).contains("RD_WP1_V3"));
    }

    @Test
    void shouldNotCreateRevisionWhenChecksumUnchanged() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-1"));
        AtomicReference<FeishuDocumentSnapshot> snapshot = new AtomicReference<>(snapshot("10", "same body RD_WP1_SAME"));
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace.mutations(), source -> snapshot.get());

        KnowledgeDocument first = importDoc(importer, base.id());
        snapshot.set(snapshot("11", "same body RD_WP1_SAME"));
        KnowledgeDocument second = importDoc(importer, base.id());

        assertEquals(first.id(), second.id());
        assertEquals(1L, second.syncVersion());
        assertEquals(1, workspace.listRevisions(first.id()).size());
        assertEquals(first.checksum(), second.checksum());
    }

    @Test
    void shouldKeepDocumentIdAndSyncVersionWhenRechunking() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-1"));
        KnowledgeDocument document = workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                base.id(),
                "rechunk.md",
                "api",
                "text/markdown",
                "# Rechunk\nOrderService.create validates amount.".getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                64,
                8
        ));
        long syncVersion = document.syncVersion();

        KnowledgeDocument rechunked = workspace.rechunkDocument(document.id(), ChunkingMode.STRUCTURE_AWARE, 32, 4);

        assertEquals(document.id(), rechunked.id());
        assertEquals(syncVersion, rechunked.syncVersion());
        assertEquals(document.checksum(), rechunked.checksum());
        assertEquals(1, workspace.listRevisions(document.id()).size());
        assertFalse(workspace.listChunks(document.id()).isEmpty());
    }

    @Test
    void shouldSoftDeleteDocumentAndHideTombFromDefaultLists() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-1"));
        KnowledgeDocument document = workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                base.id(),
                "gone.md",
                "api",
                "text/markdown",
                "# Gone\n".getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                32,
                4
        ));

        workspace.deleteDocument(document.id());

        assertTrue(workspace.listDocuments(base.id()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workspace.getDocument(document.id()));
        assertTrue(workspace.vectorStore().vectorSearch("Gone", List.of(base.id()), 8).isEmpty());
    }

    @Test
    void shouldMarkKnowledgeBaseDeletingAndHideItFromDefaultLists() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-1"));
        workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                base.id(),
                "kb-doc.md",
                "api",
                "text/markdown",
                "# KB\n".getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                32,
                4
        ));

        workspace.deleteBase(base.id());

        assertTrue(workspace.listBases().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workspace.getBase(base.id()));
        assertEquals(KnowledgeBaseLifecycle.DELETING, workspace.inspectBase(base.id()).lifecycleStatus());
        assertTrue(workspace.listDocuments(base.id()).isEmpty());
    }

    @Test
    void shouldMarkManualChunksAsLocalOnlyOverride() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-1"));
        KnowledgeDocument document = workspace.writeDocument(new WriteKnowledgeDocumentCommand(
                base.id(),
                "manual.md",
                "api",
                "text/markdown",
                "# Manual\n".getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                32,
                4
        ));

        KnowledgeChunk created = workspace.createChunk(document.id(), "manual-1", 9, "手工补充");
        assertEquals("LOCAL_ONLY_OVERRIDE", created.metadata().get("rd.projection_mode"));
        assertTrue(workspace.getDocument(document.id()).localOnlyOverride());

        KnowledgeChunk pipeline = workspace.listChunks(document.id()).getFirst();
        workspace.updateChunk(document.id(), pipeline.id(), "改过的管线块");
        assertEquals(
                "LOCAL_ONLY_OVERRIDE",
                workspace.getChunk(pipeline.id()).metadata().get("rd.projection_mode")
        );
        assertEquals(1L, workspace.getDocument(document.id()).syncVersion());
    }

    @Test
    void shouldRejectASecondActiveDocumentForTheSameSourceIdentity() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-6"));
        KnowledgeDocumentSource source = feishuSource("Wp6DuplicateToken", "7");
        workspace.writeDocument(identityCommand(base.id(), "first.md", "# First\n"), source);

        DuplicateSourceIdentityException rejected = assertThrows(
                DuplicateSourceIdentityException.class,
                () -> workspace.writeDocument(identityCommand(base.id(), "second.md", "# Second\n"), source)
        );

        assertTrue(rejected.getMessage().contains(base.id()));
        assertEquals(1, workspace.listDocuments(base.id()).size());
    }

    /** 本地匿名上传没有身份键，重复预检必须放行，否则第二次上传就会被误拒。 */
    @Test
    void shouldStillAllowRepeatedAnonymousLocalUploads() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-6"));

        KnowledgeDocument first = workspace.writeDocument(
                identityCommand(base.id(), "local-a.md", "# A\n"),
                KnowledgeDocumentSource.local()
        );
        KnowledgeDocument second = workspace.writeDocument(
                identityCommand(base.id(), "local-b.md", "# B\n"),
                KnowledgeDocumentSource.local()
        );

        assertFalse(first.id().equals(second.id()));
        assertEquals(2, workspace.listDocuments(base.id()).size());
    }

    /** 软删除后同身份必须可以重新建，墓碑不参与活动唯一性。 */
    @Test
    void shouldAllowReimportOfTheSameIdentityAfterTombstone() {
        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-6"));
        KnowledgeDocumentSource source = feishuSource("Wp6TombstoneToken", "1");
        KnowledgeDocument first = workspace.writeDocument(identityCommand(base.id(), "gone.md", "# Gone\n"), source);
        workspace.deleteDocument(first.id());

        KnowledgeDocument reborn = workspace.writeDocument(
                identityCommand(base.id(), "back.md", "# Back\n"),
                source
        );

        assertFalse(first.id().equals(reborn.id()));
        assertEquals(1, workspace.listDocuments(base.id()).size());
    }

    /**
     * 并发首建：输家在提交时撞上 active-only 唯一索引，但调用方要的是「这个来源的内容变成
     * 最新」，赢家已经把文档建好，所以必须重扫一次原地收敛，而不是把冲突抛给调用方。
     */
    @Test
    void shouldAdoptTheWinnerWhenAConcurrentWriterCreatesTheSameSourceFirst() {
        InMemoryKnowledgeBaseStore baseStore = new InMemoryKnowledgeBaseStore();
        InMemoryKnowledgeDocumentStore documentStore = new InMemoryKnowledgeDocumentStore();
        InMemoryKnowledgeChunkStore chunkStore = new InMemoryKnowledgeChunkStore();
        InMemoryKnowledgeDocumentRevisionStore revisionStore = new InMemoryKnowledgeDocumentRevisionStore();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        KnowledgeMutationTransactionPort delegate = new InMemoryKnowledgeMutationTransactionAdapter(
                documentStore,
                revisionStore,
                chunkStore,
                vectorStore,
                bindings,
                new InMemoryKnowledgeExternalIndexOutboxStore(),
                baseStore
        );
        AtomicBoolean raceLost = new AtomicBoolean(false);
        KnowledgeMutationTransactionPort racing = new RaceLosingTransactionPort(delegate, documentStore, raceLost);
        KnowledgeWorkspace workspace = KnowledgeWorkspace.withStores(
                vectorStore,
                SnowflakeIdGenerator.defaultGenerator(),
                baseStore,
                documentStore,
                chunkStore,
                revisionStore,
                new KnowledgeDocumentMutationEngine(
                        SnowflakeIdGenerator.defaultGenerator(),
                        baseStore,
                        documentStore,
                        revisionStore,
                        chunkStore,
                        racing,
                        null,
                        bindings
                )
        );
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("身份", "WP-6"));
        KnowledgeDocumentSource source = feishuSource("Wp6RaceToken", "1");

        KnowledgeDocument adopted = workspace.mutations().writeDocumentIfChanged(
                identityCommand(base.id(), "race.md", "# Race\n"),
                source
        );

        assertTrue(raceLost.get(), "并发首建必须真的撞过一次冲突");
        assertEquals(1, workspace.listDocuments(base.id()).size());
        assertEquals(workspace.listDocuments(base.id()).getFirst().id(), adopted.id());
    }

    /**
     * 模拟并发首建的输家：第一次新建提交时，赢家的行已经落库（扫描时还看不到），
     * 提交撞唯一索引。之后的提交正常放行。
     */
    private static final class RaceLosingTransactionPort implements KnowledgeMutationTransactionPort {

        private final KnowledgeMutationTransactionPort delegate;
        private final KnowledgeDocumentStore documentStore;
        private final AtomicBoolean raceLost;

        private RaceLosingTransactionPort(
                KnowledgeMutationTransactionPort delegate,
                KnowledgeDocumentStore documentStore,
                AtomicBoolean raceLost
        ) {
            this.delegate = delegate;
            this.documentStore = documentStore;
            this.raceLost = raceLost;
        }

        @Override
        public KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle) {
            if (bundle.document() != null && !raceLost.get() && !bundle.document().sourceIdentityKey().isBlank()) {
                raceLost.set(true);
                documentStore.save(bundle.document(), bundle.rawContent());
                throw new DuplicateSourceIdentityException("another writer won the race");
            }
            return delegate.commit(bundle);
        }

        @Override
        public KnowledgeProjectionBackfillCommitResult commitBackfill(KnowledgeProjectionBackfillBundle bundle) {
            return delegate.commitBackfill(bundle);
        }

        @Override
        public void commitSupersede(KnowledgeProjectionSupersedeBundle bundle) {
            delegate.commitSupersede(bundle);
        }
    }

    private static KnowledgeDocumentSource feishuSource(String token, String revisionId) {
        return new KnowledgeDocumentSource("FEISHU", token, FEISHU_URL, revisionId, 1_780_000_000_000L, 0L);
    }

    private static WriteKnowledgeDocumentCommand identityCommand(String baseId, String name, String body) {
        return new WriteKnowledgeDocumentCommand(
                baseId,
                name,
                "api",
                "text/markdown",
                body.getBytes(StandardCharsets.UTF_8),
                ChunkingMode.STRUCTURE_AWARE,
                32,
                4
        );
    }

    private static KnowledgeDocument importDoc(FeishuDocKnowledgeImporter importer, String knowledgeBaseId) {
        return importer.importDocument(new FeishuDocImportCommand(
                knowledgeBaseId,
                FEISHU_URL,
                "product-plan",
                64,
                8
        ));
    }

    private static FeishuDocumentSnapshot snapshot(String revisionId, String content) {
        return new FeishuDocumentSnapshot(
                "Wp1IdentityToken",
                FEISHU_URL,
                "身份文档",
                revisionId,
                content,
                1_780_000_000_000L
        );
    }
}
