package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenVikingProductionBoundaryPolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void productionWritersMustNotComposeStoreMutations() throws Exception {
        List<String> writers = List.of(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/knowledge/KnowledgeAdminController.java",
                "rag/src/main/java/com/wish/rd/rag/knowledge/FeishuDocKnowledgeImporter.java",
                "rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshScheduler.java",
                "rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java"
        );
        for (String writer : writers) {
            String source = Files.readString(PROJECT_ROOT.resolve(writer));
            assertFalse(source.contains("documentStore.save"), writer + " must not save documents directly");
            assertFalse(source.contains("chunkStore.save"), writer + " must not save chunks directly");
            assertFalse(source.contains("vectorStore.index"), writer + " must not index vectors directly");
            assertFalse(source.contains("workspace.writeDocument("), writer + " must not call workspace.writeDocument");
            assertFalse(source.contains("workspace.deleteDocument("), writer + " must not call workspace.deleteDocument");
            assertFalse(source.contains("workspace.createChunk("), writer + " must not call workspace.createChunk");
            assertFalse(source.contains("workspace.rechunkDocument("), writer + " must not call workspace.rechunkDocument");
        }
        String importer = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/FeishuDocKnowledgeImporter.java"));
        assertTrue(importer.contains("KnowledgeDocumentMutationPort"));
        String registry = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java"));
        assertTrue(registry.contains("workspace.mutations()"));
    }
}
