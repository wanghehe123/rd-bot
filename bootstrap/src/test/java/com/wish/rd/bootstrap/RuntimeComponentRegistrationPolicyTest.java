package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeComponentRegistrationPolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void runtimeConfigurationDoesNotConstructProjectRuntimeComponents() throws Exception {
        String configuration = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/config/RdBotRuntimeConfiguration.java"));

        assertThat(configuration)
                .doesNotContain("snowflakeIdGenerator(")
                .doesNotContain("vectorStore(")
                .doesNotContain("knowledgeBaseStore(")
                .doesNotContain("knowledgeDocumentStore(")
                .doesNotContain("knowledgeChunkStore(")
                .doesNotContain("ingestionTaskStore(")
                .doesNotContain("repairRecordRepository(")
                .doesNotContain("knowledgeWorkspace(")
                .doesNotContain("feishuDocumentClient(")
                .doesNotContain("feishuDocKnowledgeImporter(")
                .doesNotContain("knowledgeAdminEngine(")
                .doesNotContain("ingestionAdminRegistry(")
                .doesNotContain("ingestionAdminEngine(")
                .doesNotContain("objectStorageService(")
                .doesNotContain("ragTraceStore(")
                .doesNotContain("conversationRegistry(")
                .doesNotContain("conversationMemoryService(")
                .doesNotContain("conversationAdminEngine(")
                .doesNotContain("messageFeedbackRegistry(")
                .doesNotContain("messageFeedbackAdminEngine(")
                .doesNotContain("queryTermMappingRegistry(")
                .doesNotContain("queryTermMappingAdminEngine(")
                .doesNotContain("intentTreeRegistry(")
                .doesNotContain("intentTreeAdminEngine(")
                .doesNotContain("sampleQuestionRegistry(")
                .doesNotContain("sampleQuestionAdminEngine(")
                .doesNotContain("ragStreamTaskRegistry(")
                .doesNotContain("ragBugFixEngine(");
    }

    @Test
    void previousRuntimeConfigurationClassesUseSpringStereotypes() throws Exception {
        Map<String, String> componentFiles = Map.ofEntries(
                Map.entry("rag/src/main/java/com/wish/rd/framework/id/SnowflakeIdGenerator.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/vector/impl/InMemoryVectorStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresVectorStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/store/impl/InMemoryKnowledgeBaseStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeBaseStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/store/impl/InMemoryKnowledgeDocumentStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeDocumentStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/store/impl/InMemoryKnowledgeDocumentRevisionStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeDocumentRevisionStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/store/impl/InMemoryKnowledgeChunkStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeChunkStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/projection/impl/InMemoryKnowledgeExternalIndexBindingStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeExternalIndexBindingStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/projection/impl/InMemoryKnowledgeExternalIndexOutboxStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeExternalIndexOutboxStore.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/projection/impl/InMemoryKnowledgeMutationTransactionAdapter.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeMutationTransactionAdapter.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeDocumentMutationEngine.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/ingestion/impl/InMemoryIngestionTaskStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresIngestionTaskStore.java", "@Component"),
                Map.entry("exec/src/main/java/com/wish/rd/exec/repair/impl/InMemoryRepairRecordRepository.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRepairRecordRepository.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeWorkspace.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/impl/MockFeishuDocumentClient.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/knowledge/FeishuDocKnowledgeImporter.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/ingestion/impl/InMemoryObjectStorageService.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/storage/impl/S3ObjectStorageService.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/trace/RagTraceStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/QueryTermMappingConfiguration.java", "@Configuration"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/intent/IntentTreeRegistry.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java", "@Component"),
                Map.entry("rag/src/main/java/com/wish/rd/rag/runtime/impl/InMemoryRdTaskStore.java", "@Component"),
                Map.entry("bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdTaskStore.java", "@Component"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/admin/knowledge/KnowledgeAdminEngine.java", "@Service"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/admin/ingestion/IngestionAdminEngine.java", "@Service"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/admin/rewrite/QueryTermMappingAdminEngine.java", "@Service"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/admin/intent/IntentTreeAdminEngine.java", "@Service"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/rag/RagBugFixEngine.java", "@Service"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java", "@Service"),
                Map.entry("engine/src/main/java/com/wish/rd/engine/bugfix/BugFixPromptBuilder.java", "@Component")
        );

        for (Map.Entry<String, String> entry : componentFiles.entrySet()) {
            String source = Files.readString(PROJECT_ROOT.resolve(entry.getKey()));

            assertThat(source)
                    .as(entry.getKey())
                    .contains(entry.getValue());
        }
    }
}
