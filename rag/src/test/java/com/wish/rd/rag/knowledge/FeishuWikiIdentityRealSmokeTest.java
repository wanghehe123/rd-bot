package com.wish.rd.rag.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocImportCommand;
import com.wish.rd.rag.knowledge.model.FeishuDocumentSnapshot;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionWakePort;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实飞书 wiki 导入身份冒烟。默认跳过；需本机已登录 {@code lark-cli} 用户身份，再加
 * {@code -Drd.feishu.docs.smoke=true}。
 */
@EnabledIfSystemProperty(named = "rd.feishu.docs.smoke", matches = "true")
class FeishuWikiIdentityRealSmokeTest {

    private static final String SOURCE = "https://my.feishu.cn/wiki/IJHrwvwaDiicFpkID8Fcxvsun0g";
    private static final String WIKI_TOKEN = "IJHrwvwaDiicFpkID8Fcxvsun0g";

    @Test
    void shouldKeepOneDocumentIdWhenReimportingLiveWiki() throws Exception {
        FeishuDocumentSnapshot snapshot = fetchLiveWiki(SOURCE);
        assertEquals(WIKI_TOKEN, snapshot.sourceToken());
        assertFalse(snapshot.revisionId().isBlank());
        assertTrue(snapshot.content().contains("RD-Bot"));
        assertTrue(snapshot.content().contains("自动修复"));

        KnowledgeWorkspace workspace = KnowledgeWorkspace.inMemory();
        KnowledgeBase base = workspace.createBase(new CreateKnowledgeBaseCommand("飞书真实导入", "WP-1 live"));
        FeishuDocKnowledgeImporter importer = new FeishuDocKnowledgeImporter(workspace.mutations(), ignored -> snapshot);

        KnowledgeDocument first = importDoc(importer, base.id());
        KnowledgeDocument second = importDoc(importer, base.id());

        assertEquals(first.id(), second.id());
        assertEquals(1, workspace.listDocuments(base.id()).size());
        assertEquals(1L, second.syncVersion());
        assertEquals(1, workspace.listRevisions(first.id()).size());
        assertEquals("FEISHU", first.sourceType());
        assertEquals(WIKI_TOKEN, first.sourceToken());
        assertEquals(snapshot.revisionId(), first.revisionId());
        assertEquals(snapshot.title(), first.sourceName());
        assertTrue(workspace.previewDocument(first.id()).contains("自动修复"));
        assertFalse(workspace.listChunks(first.id()).isEmpty());
        assertEquals(SourceIdentityKeys.from("FEISHU", WIKI_TOKEN, SOURCE), first.sourceIdentityKey());
    }

    @Test
    void shouldProjectLiveWikiImportIntoOnePendingUpsertAndFlipToAbsentOnDisable() throws Exception {
        FeishuDocumentSnapshot snapshot = fetchLiveWiki(SOURCE);
        Fixture fixture = Fixture.create();
        FeishuDocKnowledgeImporter importer =
                new FeishuDocKnowledgeImporter(fixture.engine, ignored -> snapshot);

        KnowledgeDocument document = importDoc(importer, fixture.baseId);

        List<KnowledgeExternalIndexOperation> afterFirstImport = fixture.outbox.listByDocumentId(document.id());
        assertEquals(1, afterFirstImport.size());
        assertEquals(ExternalKnowledgeOperationType.UPSERT_DOCUMENT, afterFirstImport.getFirst().operationType());
        assertEquals(ExternalKnowledgeOperationStatus.PENDING, afterFirstImport.getFirst().status());
        assertEquals(1L, afterFirstImport.getFirst().syncVersion());
        assertEquals(document.checksum(), afterFirstImport.getFirst().checksum());

        KnowledgeExternalIndexBinding binding = fixture.bindings
                .findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, document.id())
                .orElseThrow();
        assertEquals(ExternalKnowledgeDesiredState.PRESENT, binding.desiredState());
        assertEquals(ExternalKnowledgeObservedState.UNKNOWN, binding.observedState());
        assertEquals(
                OpenVikingProjectionUris.documentRootUri(fixture.baseId, document.id()),
                binding.remoteUri());
        assertEquals(
                OpenVikingProjectionUris.ownershipMarker(fixture.baseId, document.id()),
                binding.ownershipMarker());
        assertEquals(document.checksum(), binding.desiredChecksum());

        KnowledgeDocument reimported = importDoc(importer, fixture.baseId);
        assertEquals(document.id(), reimported.id());
        assertEquals(1L, reimported.syncVersion());
        assertEquals(1, fixture.outbox.listByDocumentId(document.id()).size());

        KnowledgeDocument disabled = fixture.engine.setDocumentEnabled(document.id(), false);
        assertEquals(2L, disabled.syncVersion());
        assertTrue(fixture.outbox.listByDocumentId(document.id()).stream().anyMatch(operation ->
                operation.operationType() == ExternalKnowledgeOperationType.DELETE_DOCUMENT
                        && operation.syncVersion() == 2L
                        && operation.status() == ExternalKnowledgeOperationStatus.PENDING));
        assertEquals(
                ExternalKnowledgeDesiredState.ABSENT,
                fixture.bindings
                        .findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, document.id())
                        .orElseThrow()
                        .desiredState());
    }

    private record Fixture(
            KnowledgeDocumentMutationEngine engine,
            InMemoryKnowledgeExternalIndexBindingStore bindings,
            InMemoryKnowledgeExternalIndexOutboxStore outbox,
            String baseId
    ) {

        static Fixture create() {
            InMemoryKnowledgeBaseStore bases = new InMemoryKnowledgeBaseStore();
            InMemoryKnowledgeDocumentStore documents = new InMemoryKnowledgeDocumentStore();
            InMemoryKnowledgeChunkStore chunks = new InMemoryKnowledgeChunkStore();
            InMemoryKnowledgeDocumentRevisionStore revisions = new InMemoryKnowledgeDocumentRevisionStore();
            InMemoryVectorStore vectors = new InMemoryVectorStore();
            InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
            InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
            SnowflakeIdGenerator idGenerator = SnowflakeIdGenerator.defaultGenerator();
            KnowledgeDocumentMutationEngine engine = new KnowledgeDocumentMutationEngine(
                    idGenerator,
                    bases,
                    documents,
                    revisions,
                    chunks,
                    new InMemoryKnowledgeMutationTransactionAdapter(
                            documents, revisions, chunks, vectors, bindings, outbox, bases),
                    KnowledgeProjectionWakePort.noop()
            );
            KnowledgeWorkspace workspace = KnowledgeWorkspace.withStores(
                    vectors, idGenerator, bases, documents, chunks, revisions, engine);
            KnowledgeBase base = workspace.createBase(
                    new CreateKnowledgeBaseCommand("飞书真实投影", "WP-2 live"));
            return new Fixture(engine, bindings, outbox, base.id());
        }
    }

    private static KnowledgeDocument importDoc(FeishuDocKnowledgeImporter importer, String knowledgeBaseId) {
        return importer.importDocument(new FeishuDocImportCommand(
                knowledgeBaseId,
                SOURCE,
                "product-plan",
                64,
                8
        ));
    }

    private static FeishuDocumentSnapshot fetchLiveWiki(String source) throws Exception {
        Path snapshotFile = Path.of(System.getProperty("rd.feishu.docs.smoke.snapshot", ""));
        JsonNode document;
        if (Files.isRegularFile(snapshotFile)) {
            document = new ObjectMapper().readTree(Files.readString(snapshotFile))
                    .path("data")
                    .path("document");
        } else {
            document = fetchViaLarkCli(source).path("data").path("document");
        }
        String content = document.path("content").asText();
        assertFalse(content.isBlank(), "live wiki content must not be blank");
        String title = titleFromMarkdown(content);
        return new FeishuDocumentSnapshot(
                WIKI_TOKEN,
                source,
                title,
                document.path("revision_id").asText(),
                content,
                System.currentTimeMillis()
        );
    }

    private static JsonNode fetchViaLarkCli(String source) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "lark-cli",
                "docs",
                "+fetch",
                "--as",
                "user",
                "--doc",
                source,
                "--doc-format",
                "markdown",
                "--format",
                "json"
        );
        builder.environment().put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");
        builder.environment().put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(finished, "lark-cli docs +fetch timed out");
        assertEquals(0, process.exitValue(), output);
        JsonNode root = new ObjectMapper().readTree(output);
        assertTrue(root.path("ok").asBoolean(false), output);
        return root;
    }

    private static String titleFromMarkdown(String content) {
        for (String line : List.of(content.split("\n", -1))) {
            String stripped = line.strip();
            if (stripped.startsWith("# ")) {
                return stripped.substring(2).strip();
            }
        }
        return "RD-Bot";
    }
}
