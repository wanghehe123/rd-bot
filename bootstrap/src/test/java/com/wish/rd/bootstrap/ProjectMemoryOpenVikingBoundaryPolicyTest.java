package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryOpenVikingBoundaryPolicyTest {

    private static final Pattern FORBIDDEN_TYPE_NAMES = Pattern.compile(
            "class\\s+(ProjectMemory(Outbox|ExternalIndex|ProjectionWorker|ProjectionAdapter)"
                    + "|OpenViking(ProjectMemory|Memory(Adapter|Worker|Outbox|Binding)))"
                    + "|interface\\s+(ProjectMemory(Outbox|ExternalIndex)Store)");
    private static final Pattern FORBIDDEN_TABLES = Pattern.compile(
            "rd_project_memory_(outbox|bindings|projection)");
    private static final List<String> LOCAL_HEAD_AND_DELIVERY_PATHS = List.of(
            "engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryConsolidationEngine.java",
            "engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryResolver.java",
            "engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryOperationWorker.java",
            "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresProjectMemoryStore.java",
            "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageFinalizationAdapter.java",
            "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresProjectMemorySearchPort.java",
            "bootstrap/src/main/java/com/wish/rd/bootstrap/rag/impl/ProjectScopedRequirementKnowledgeSearchAdapter.java"
    );

    @Test
    void thisChangeMustNotCreateOpenVikingMemoryOutboxBindingAdapterOrWorker() throws IOException {
        Path projectRoot = projectRoot();
        List<String> violations = new ArrayList<>();
        for (String moduleJava : List.of(
                "rag/src/main/java",
                "engine/src/main/java",
                "bootstrap/src/main/java"
        )) {
            Path root = projectRoot.resolve(moduleJava);
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    String source = read(path);
                    if (FORBIDDEN_TYPE_NAMES.matcher(source).find()) {
                        violations.add(path + " defines a forbidden OpenViking memory type");
                    }
                    String fileName = path.getFileName().toString();
                    if (isForbiddenOpenVikingMemoryArtifact(fileName)) {
                        violations.add(path + " is a forbidden OpenViking memory artifact");
                    }
                });
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static boolean isForbiddenOpenVikingMemoryArtifact(String fileName) {
        return fileName.contains("ProjectMemoryOutbox")
                || fileName.contains("ProjectMemoryExternalIndex")
                || fileName.contains("ProjectMemoryIndexBinding")
                || fileName.contains("ProjectMemoryProjectionWorker")
                || fileName.contains("ProjectMemoryProjectionAdapter")
                || (fileName.contains("OpenViking") && fileName.contains("Memory"));
    }

    @Test
    void postgresIsTheOnlyProductionProjectMemoryRetrievalPath() throws IOException {
        Path projectRoot = projectRoot();
        String searchPort = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresProjectMemorySearchPort.java"));
        assertTrue(searchPort.contains("implements ProjectMemorySearchPort"));
        assertTrue(searchPort.contains("@ConditionalOnProperty(name = \"rd.knowledge.store\", havingValue = \"postgres\")"));
        assertTrue(searchPort.contains("memoryMapper.searchActiveHeadsLexical"));
        assertFalse(searchPort.contains("OpenViking"));
        assertFalse(searchPort.contains("ExternalKnowledgeNavigatorPort"));

        String adapter = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/rag/impl/"
                        + "ProjectScopedRequirementKnowledgeSearchAdapter.java"));
        assertTrue(adapter.contains("ProjectMemoryRetrievalChannel"));
        assertFalse(adapter.contains("ExternalKnowledgeNavigatorPort"));
        assertFalse(adapter.contains("OpenVikingHttpExchange"));
        assertFalse(adapter.contains("OpenVikingRestNavigatorAdapter"));

        List<String> extraImplementations = new ArrayList<>();
        for (String moduleJava : List.of(
                "rag/src/main/java",
                "engine/src/main/java",
                "bootstrap/src/main/java"
        )) {
            try (Stream<Path> files = Files.walk(projectRoot.resolve(moduleJava))) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    String name = path.getFileName().toString();
                    String source = read(path);
                    if (source.contains("implements ProjectMemorySearchPort")
                            && !name.equals("InMemoryProjectMemorySearchPort.java")
                            && !name.equals("PostgresProjectMemorySearchPort.java")) {
                        extraImplementations.add(path.toString());
                    }
                });
            }
        }
        assertTrue(extraImplementations.isEmpty(),
                () -> "only in-memory contract and PostgreSQL search ports may exist:\n"
                        + String.join("\n", extraImplementations));
    }

    @Test
    void applicationDefaultsMustKeepFutureMemoryProjectionOff() throws IOException {
        String yaml = Files.readString(projectRoot().resolve("bootstrap/src/main/resources/application.yaml"));
        assertTrue(yaml.contains("mode: ${RD_PROJECT_MEMORY_PROJECTION_MODE:OFF}"),
                "project-memory projection must stay opt-in at OFF");
        assertTrue(yaml.contains("enabled: ${RD_PROJECT_MEMORY_PROJECTION_ENABLED:false}"),
                "project-memory projection enabled flag must default false");
        int projectMemory = yaml.indexOf("  project-memory:");
        assertTrue(projectMemory > 0);
        String block = yaml.substring(projectMemory, yaml.indexOf("  openviking:", projectMemory));
        assertTrue(block.contains("projection:"));
        assertFalse(block.toLowerCase(Locale.ROOT).contains("openviking"),
                "project-memory config must not point at OpenViking in this change");
    }

    @Test
    void p19MustNotCreateMemoryProjectionTables() throws IOException {
        String sql = Files.readString(projectRoot().resolve(
                "bootstrap/src/main/resources/sql/postgres/p19_project_agent_memory.sql"));
        assertFalse(FORBIDDEN_TABLES.matcher(sql).find());
        assertFalse(sql.toLowerCase(Locale.ROOT).contains("openviking"));
        assertFalse(sql.contains("viking://"));
        assertFalse(sql.contains("knowledge_external_index"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_project_memories"));
        assertTrue(sql.contains("no external projection is enabled here"));
    }

    @Test
    void openVikingKnowledgeProjectionMustNotGainAMemoryOwnedRootOrWorker() throws IOException {
        Path projectRoot = projectRoot();
        String uris = Files.readString(projectRoot.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/projection/OpenVikingProjectionUris.java"));
        assertFalse(uris.contains("/memories/"));
        assertFalse(uris.contains("project-memory"));
        assertFalse(uris.contains("rd-memory://"));

        String configuration = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/OpenVikingProjectionConfiguration.java"));
        assertFalse(configuration.contains("ProjectMemory"));
        assertFalse(configuration.contains("project-memory"));
        assertFalse(configuration.contains("rd-memory://"));

        Path openvikingRoot = projectRoot.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap/openviking");
        try (Stream<Path> files = Files.walk(openvikingRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                assertFalse(source.contains("ProjectMemory"),
                        path + " must not wire project memory into OpenViking knowledge projection");
            });
        }
    }

    @Test
    void openVikingFailureCannotAffectLocalHeadOrDelivery() throws IOException {
        for (String relative : LOCAL_HEAD_AND_DELIVERY_PATHS) {
            String source = Files.readString(projectRoot().resolve(relative));
            assertFalse(source.contains("OpenViking"), relative + " must not import OpenViking");
            assertFalse(source.contains("ExternalKnowledgeIndexPort"),
                    relative + " must not call the knowledge index port");
            assertFalse(source.contains("KnowledgeExternalIndexOutboxStore"),
                    relative + " must not enqueue knowledge outbox rows for memory");
            assertFalse(source.contains("submitUpsert("), relative + " must not submit remote writes");
            assertFalse(source.contains("OpenVikingHttpExchange"));
            if (!relative.endsWith("ProjectMemoryProjectionConfiguration.java")) {
                assertFalse(source.contains("ProjectMemoryProjectionPort"),
                        relative + " must not invoke projection during local head or delivery");
            }
        }
    }

    private static Path projectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("RULE.md"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("RULE.md"))) {
            return parent;
        }
        throw new IllegalStateException("cannot locate project root from " + cwd);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read " + path, exception);
        }
    }
}
