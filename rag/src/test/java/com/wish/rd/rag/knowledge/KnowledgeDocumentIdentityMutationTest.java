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
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace, source -> snapshot.get());

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
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace, source -> snapshot.get());

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
