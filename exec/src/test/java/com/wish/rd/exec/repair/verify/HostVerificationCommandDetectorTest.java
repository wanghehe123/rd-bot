package com.wish.rd.exec.repair.verify;

import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;
import com.wish.rd.exec.repair.verify.model.HostVerificationCommandSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVerificationCommandDetectorTest {

    @TempDir
    Path repository;

    private final HostVerificationCommandDetector detector = new HostVerificationCommandDetector();

    @Test
    void shouldDetectNpmBuildTestAndTypecheckFromPackageJson() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "build": "tsc -p tsconfig.json",
                    "test": "node --test",
                    "typecheck": "tsc --noEmit"
                  }
                }
                """);
        Files.writeString(repository.resolve("package-lock.json"), "{}");

        HostVerificationCommandSet commands = detector.detect(repository, List.of("src/index.ts"));

        assertEquals(List.of("npm ci", "npm test", "npm run build"), commands.buildCommands());
        assertEquals(List.of("npm run typecheck"), commands.staticCommands());
        assertFalse(commands.buildCommandsDeclared());
        assertFalse(commands.staticCommandsDeclared());
        assertFalse(commands.docsOnly());
        assertFalse(commands.ambiguous());
    }

    @Test
    void shouldDetectViteProductionBuildAndNeverUseDev() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build"
                  },
                  "devDependencies": {
                    "vite": "latest"
                  }
                }
                """);

        HostVerificationCommandSet commands = detector.detect(repository, List.of("src/App.tsx"));

        assertTrue(commands.buildCommands().contains("npm run build"));
        assertFalse(commands.buildCommands().stream().anyMatch(command -> command.contains("npm run dev")));
        assertFalse(commands.docsOnly());
        assertFalse(commands.ambiguous());
    }

    @Test
    void shouldDetectNextJsProductionBuildAndNeverUseNextDev() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "dev": "next dev",
                    "build": "next build",
                    "start": "next start"
                  },
                  "dependencies": {
                    "next": "latest",
                    "react": "latest"
                  }
                }
                """);

        HostVerificationCommandSet commands = detector.detect(repository, List.of("src/app/page.tsx"));

        assertTrue(commands.buildCommands().contains("npm run build"));
        assertFalse(commands.buildCommands().stream().anyMatch(command ->
                command.contains("next dev") || command.contains("npm run dev")));
        assertFalse(commands.docsOnly());
        assertFalse(commands.ambiguous());
    }

    @Test
    void shouldDetectMavenWrapperTestAsBuild() throws Exception {
        Files.writeString(repository.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>acme</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path mvnw = repository.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\n");
        assertTrue(mvnw.toFile().setExecutable(true));

        HostVerificationCommandSet commands = detector.detect(repository, List.of("src/main/java/Demo.java"));

        assertEquals(List.of("./mvnw -q test"), commands.buildCommands());
        assertTrue(commands.staticCommands().isEmpty());
        assertFalse(commands.buildCommandsDeclared());
        assertFalse(commands.docsOnly());
        assertFalse(commands.ambiguous());
    }

    @Test
    void shouldMarkEmptyRepositoryAsAmbiguous() {
        HostVerificationCommandSet commands = detector.detect(repository, List.of("src/unknown.bin"));

        assertTrue(commands.ambiguous());
        assertTrue(commands.buildCommands().isEmpty());
        assertTrue(commands.staticCommands().isEmpty());
        assertFalse(commands.docsOnly());
        assertFalse(commands.buildCommandsDeclared());
        assertFalse(commands.staticCommandsDeclared());
        assertTrue(commands.reason().toLowerCase().contains("cannot detect"));
    }

    @Test
    void shouldDetectNestedClientAndServerBuildWithoutRootBuildScript() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "install:all": "cd server && npm install && cd ../client && npm install",
                    "dev": "vite",
                    "start": "node server/index.js",
                    "seed": "node seed.js"
                  }
                }
                """);
        Files.createDirectories(repository.resolve("client"));
        Files.createDirectories(repository.resolve("server"));
        Files.writeString(repository.resolve("client/package.json"), """
                {
                  "scripts": {
                    "dev": "vite",
                    "build": "tsc && vite build"
                  }
                }
                """);
        Files.writeString(repository.resolve("server/package.json"), """
                {
                  "scripts": {
                    "dev": "tsx watch src/index.ts",
                    "build": "tsc"
                  }
                }
                """);
        Files.writeString(repository.resolve("client/tsconfig.json"), "{}");
        Files.writeString(repository.resolve("server/tsconfig.json"), "{}");

        HostVerificationCommandSet commands = detector.detect(
                repository,
                List.of("client/src/pages/customer/Home.tsx")
        );

        assertEquals(
                List.of(
                        "npm --prefix server install",
                        "npm --prefix server run build",
                        "npm --prefix client install",
                        "npm --prefix client run build"
                ),
                commands.buildCommands()
        );
        assertTrue(commands.staticCommands().isEmpty());
        assertFalse(commands.staticCommands().stream().anyMatch(command -> command.contains("tsc --noEmit")));
        assertFalse(commands.buildCommands().stream().anyMatch(command ->
                command.contains("npm run dev") || command.contains("cd ")));
        assertFalse(commands.ambiguous());
        assertFalse(commands.docsOnly());
        assertFalse(commands.buildCommandsDeclared());
    }

    @Test
    void shouldStayAmbiguousWhenRootOnlyHasInstallAllAndDev() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "install:all": "echo install",
                    "dev": "vite",
                    "start": "node server.js",
                    "seed": "node seed.js"
                  }
                }
                """);

        HostVerificationCommandSet commands = detector.detect(repository, List.of("src/App.tsx"));

        assertTrue(commands.ambiguous());
        assertTrue(commands.buildCommands().isEmpty());
        assertFalse(commands.buildCommands().stream().anyMatch(command ->
                command.contains("npm run dev") || command.contains("install:all")));
        assertTrue(commands.reason().toLowerCase().contains("cannot detect"));
    }

    @Test
    void shouldSkipDetectionWhenChangedFilesAreDocsOnly() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "build": "vite build",
                    "test": "vitest",
                    "typecheck": "tsc --noEmit"
                  }
                }
                """);

        HostVerificationCommandSet commands = detector.detect(
                repository,
                List.of("README.md", "docs/a.md")
        );

        assertTrue(commands.docsOnly());
        assertTrue(commands.buildCommands().isEmpty());
        assertTrue(commands.staticCommands().isEmpty());
        assertFalse(commands.ambiguous());
        assertFalse(commands.buildCommandsDeclared());
        assertFalse(commands.staticCommandsDeclared());
        assertTrue(commands.reason().toLowerCase().contains("docs-only"));
    }

    @Test
    void shouldSkipBuildWhenExplicitProfileDeclaresEmptyBuildCommands() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "build": "vite build",
                    "test": "vitest",
                    "typecheck": "tsc --noEmit"
                  }
                }
                """);
        QaExecutionProfile profile = explicitProfile(List.of(), true, List.of(), false);

        HostVerificationCommandSet commands = detector.detect(
                repository,
                List.of("src/App.tsx"),
                profile
        );

        assertTrue(commands.buildCommandsDeclared());
        assertTrue(commands.buildCommands().isEmpty());
        assertFalse(commands.staticCommandsDeclared());
        assertEquals(List.of("npm run typecheck"), commands.staticCommands());
        assertFalse(commands.docsOnly());
        assertFalse(commands.ambiguous());
    }

    @Test
    void shouldPreferExplicitBuildCommandsOverAutoDetect() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {
                  "scripts": {
                    "build": "vite build",
                    "test": "vitest"
                  }
                }
                """);
        Files.writeString(repository.resolve("package-lock.json"), "{}");
        QaExecutionProfile profile = explicitProfile(List.of("npm run build"), true, List.of(), false);

        HostVerificationCommandSet commands = detector.detect(
                repository,
                List.of("src/App.tsx"),
                profile
        );

        assertTrue(commands.buildCommandsDeclared());
        assertEquals(List.of("npm run build"), commands.buildCommands());
        assertFalse(commands.buildCommands().contains("npm ci"));
        assertFalse(commands.buildCommands().contains("npm test"));
        assertFalse(commands.docsOnly());
        assertFalse(commands.ambiguous());
    }

    private static QaExecutionProfile explicitProfile(
            List<String> buildCommands,
            boolean buildCommandsDeclared,
            List<String> staticCommands,
            boolean staticCommandsDeclared
    ) {
        return new QaExecutionProfile(
                false,
                false,
                "TASK_OVERRIDE",
                "",
                "",
                "",
                List.of(),
                List.of(),
                buildCommands,
                staticCommands,
                buildCommandsDeclared,
                staticCommandsDeclared,
                "explicit host verification commands"
        );
    }
}
