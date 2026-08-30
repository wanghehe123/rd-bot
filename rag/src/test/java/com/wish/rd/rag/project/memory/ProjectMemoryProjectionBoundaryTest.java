package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.impl.DisabledProjectMemoryProjectionPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemorySearchPort;
import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySearchHit;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import com.wish.rd.rag.retrieval.impl.ProjectMemoryRetrievalChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryProjectionBoundaryTest {

    private static final Pattern IMPLEMENTS_SEARCH_PORT = Pattern.compile(
            "implements\\s+ProjectMemorySearchPort");
    private static final List<String> FORBIDDEN_MEMORY_PROJECTION_TYPES = List.of(
            "ProjectMemoryOutbox",
            "ProjectMemoryExternalIndex",
            "ProjectMemoryProjectionWorker",
            "ProjectMemoryProjectionAdapter",
            "OpenVikingProjectMemory",
            "OpenVikingMemoryAdapter",
            "OpenVikingMemoryWorker"
    );

    @Test
    void futureProjectionSpiDefaultsOffAndDoesNotParticipateInLocalHead() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        ProjectMemoryProjectionPort throwingPort = () -> {
            invoked.set(true);
            throw new IllegalStateException("OpenViking projection must not be consulted");
        };
        InMemoryProjectMemoryStore store = new InMemoryProjectMemoryStore();
        store.create(new ProjectMemory(
                "44", "101", "CODING_AGENT", ProjectMemoryType.PROCEDURAL, "build-cache", 1L));
        store.addRevision(new ProjectMemoryRevision(
                "99", "44", 1L, ProjectMemoryRevisionStatus.ACTIVE, "title", "summary", "{}",
                "a".repeat(64), "v1", "", 1L, 1L));
        store.advanceHead("44", "99", 1L);

        assertEquals("99", store.find("44").orElseThrow().headRevisionId());
        assertFalse(invoked.get(), "local head CAS must not call the projection SPI");
        assertTrue(throwingPort instanceof ProjectMemoryProjectionPort);

        ProjectMemoryProjectionPort disabled = new DisabledProjectMemoryProjectionPort();
        assertEquals(ProjectMemoryProjectionMode.OFF, disabled.mode());
        assertFalse(disabled.enabled());
    }

    @Test
    void projectMemoryPackagesMustNotImportOpenVikingOrKnowledgeOutbox() throws IOException {
        Path memoryRoot = projectRoot().resolve("rag/src/main/java/com/wish/rd/rag/project/memory");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(memoryRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                if (source.contains("com.wish.rd.bootstrap.openviking")
                        || source.contains("com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore")
                        || source.contains("com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore")
                        || source.contains("com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort")
                        || source.contains("com.wish.rd.rag.retrieval.navigator.ExternalKnowledgeNavigatorPort")
                        || source.contains("OpenVikingHttpExchange")
                        || source.contains("OpenVikingRestIndexAdapter")) {
                    violations.add(path.toString());
                }
                for (String forbidden : FORBIDDEN_MEMORY_PROJECTION_TYPES) {
                    if (source.contains("class " + forbidden) || source.contains("interface " + forbidden)) {
                        violations.add(path + " defines " + forbidden);
                    }
                }
            });
        }
        assertTrue(violations.isEmpty(), () -> "project memory must not create OpenViking projection wiring:\n"
                + String.join("\n", violations));
    }

    @Test
    void retrievalChannelUsesOnlyProjectMemorySearchPort() throws IOException {
        Path channel = projectRoot().resolve(
                "rag/src/main/java/com/wish/rd/rag/retrieval/impl/ProjectMemoryRetrievalChannel.java");
        String source = Files.readString(channel);
        assertTrue(source.contains("ProjectMemorySearchPort"));
        assertFalse(source.contains("ExternalKnowledgeNavigatorPort"));
        assertFalse(source.contains("OpenViking"));
        assertFalse(source.contains("WorkflowExperienceEntry"));

        List<ProjectMemorySearchHit> hits = List.of(new ProjectMemorySearchHit(
                "44", 3L, "101", "CODING_AGENT", true, true, true, 0.9d, "build", "a".repeat(64)));
        var evidence = new ProjectMemoryRetrievalChannel(new InMemoryProjectMemorySearchPort(hits))
                .retrieve("101", "CODING_AGENT", "build", 3, 0.5d, 1L);
        assertEquals("rd-memory://projects/101/memories/44/revisions/3", evidence.getFirst().sourceUri());
    }

    @Test
    void ragMustNotShipAProductionNonPostgresSearchPort() throws IOException {
        Path javaRoot = projectRoot().resolve("rag/src/main/java");
        List<String> productionPorts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(javaRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                Matcher matcher = IMPLEMENTS_SEARCH_PORT.matcher(source);
                if (matcher.find() && !path.getFileName().toString().startsWith("InMemory")) {
                    productionPorts.add(path.toString());
                }
            });
        }
        assertTrue(productionPorts.isEmpty(),
                () -> "rag must not ship a production ProjectMemorySearchPort; PostgreSQL lives in bootstrap:\n"
                        + String.join("\n", productionPorts));
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
