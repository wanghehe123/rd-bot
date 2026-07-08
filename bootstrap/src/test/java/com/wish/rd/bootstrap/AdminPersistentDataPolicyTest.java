package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPersistentDataPolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void adminIntentAndIngestionRegistriesDelegateMutableStateToStores() throws Exception {
        String intentRegistry = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/intent/IntentTreeRegistry.java"));
        String ingestionRegistry = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java"));

        assertFalse(intentRegistry.contains("LinkedHashMap"),
                "IntentTreeRegistry must not own production-visible intent data in JVM memory");
        assertFalse(intentRegistry.contains("AtomicLong"),
                "IntentTreeRegistry must not allocate production ids in JVM memory");
        assertFalse(ingestionRegistry.contains("LinkedHashMap"),
                "IngestionAdminRegistry must not own production-visible pipelines in JVM memory");
        assertFalse(ingestionRegistry.contains("AtomicLong"),
                "IngestionAdminRegistry must not allocate production ids in JVM memory");
    }

    @Test
    void postgresAdaptersExistForAllAdminVisibleMutableData() {
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/intent/IntentNodeStore.java")),
                "intent admin data must be behind an IntentNodeStore port");
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresIntentNodeStore.java")),
                "rd.knowledge.store=postgres must use PostgreSQL for intent nodes");
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/ingestion/IngestionPipelineStore.java")),
                "ingestion pipeline admin data must be behind an IngestionPipelineStore port");
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresIngestionPipelineStore.java")),
                "rd.knowledge.store=postgres must use PostgreSQL for ingestion pipelines");
    }

    @Test
    void postgresSqlDeclaresIntentAndIngestionPipelineTables() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS t_intent_node"),
                "PostgreSQL SQL must create t_intent_node for admin intent data");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS t_ingestion_pipeline"),
                "PostgreSQL SQL must create t_ingestion_pipeline for admin pipeline data");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS t_ingestion_pipeline_node"),
                "PostgreSQL SQL must create t_ingestion_pipeline_node for pipeline topology");
    }
}
