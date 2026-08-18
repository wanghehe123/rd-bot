package com.wish.rd.exec.repair.qa;

import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaNpmInstallPlanTest {

    @TempDir
    Path repository;

    @Test
    void nestedServerAndClientPackageJsonAreInstalledWithoutRootLockfile() throws Exception {
        Files.writeString(repository.resolve("package.json"), """
                {"name":"root","scripts":{"install:all":"true"}}
                """);
        Files.createDirectories(repository.resolve("server"));
        Files.writeString(repository.resolve("server").resolve("package.json"), """
                {"name":"server","dependencies":{"better-sqlite3":"9.0.0"}}
                """);
        Files.createDirectories(repository.resolve("client"));
        Files.writeString(repository.resolve("client").resolve("package.json"), """
                {"name":"client","devDependencies":{"vite":"5.0.0"}}
                """);
        Files.writeString(repository.resolve("client").resolve("package-lock.json"), "{}");

        assertEquals(List.of(".", "server", "client"), QaNpmInstallPlan.packageDirectories(repository));
        String script = QaNpmInstallPlan.shellScript(repository);
        assertTrue(script.contains("cd '.' && npm install --include=dev"), script);
        assertTrue(script.contains("cd 'server' && npm install --include=dev"), script);
        assertTrue(script.contains("cd 'client' && npm ci --include=dev"), script);
        assertTrue(QaNpmInstallPlan.required(repository, browserProfile("AUTO_DETECTION")));
    }

    @Test
    void docsOnlyAndNonBrowserProfilesSkipInstall() throws Exception {
        Files.writeString(repository.resolve("package.json"), "{\"name\":\"app\"}\n");

        assertFalse(QaNpmInstallPlan.required(repository, browserProfile("DOCS_ONLY")));
        assertFalse(QaNpmInstallPlan.required(
                repository,
                new QaExecutionProfile(false, false, "NOT_APPLICABLE", "", "", "", List.of(), "no web")
        ));
        assertFalse(QaNpmInstallPlan.required(repository, null));
    }

    private static QaExecutionProfile browserProfile(String decisionSource) {
        return new QaExecutionProfile(
                true,
                false,
                decisionSource,
                "http://127.0.0.1:5173",
                "./start.sh",
                "/",
                List.of("127.0.0.1"),
                "nested Vite"
        );
    }
}
