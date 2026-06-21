package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PackageStructureTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void bootstrapRootPackageContainsOnlyApplicationEntrypoints() throws Exception {
        Set<String> allowed = Set.of("RdBotApplication.java");
        try (var files = Files.list(PROJECT_ROOT.resolve("bootstrap/src/main/java/com/wish/rd/bootstrap"))) {
            assertThat(files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .toList())
                    .containsExactlyInAnyOrderElementsOf(allowed);
        }
    }

    @Test
    void engineRootPackageContainsOnlyLayerMarker() throws Exception {
        Set<String> allowed = Set.of("EngineLayer.java");
        try (var files = Files.list(PROJECT_ROOT.resolve("engine/src/main/java/com/wish/rd/engine"))) {
            assertThat(files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .toList())
                    .containsExactlyInAnyOrderElementsOf(allowed);
        }
    }
}
