package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host {@code DeterministicAuditor.auditQa}/{@code auditQaWithClaims} must have a production
 * caller outside {@code engine/.../audit}. Unit tests inside that package are not enough:
 * live QA otherwise only records UNTRUSTED claims and never promotes {@code AC-*}.
 */
class AuditedAuditorProductionCallSiteTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void auditQaHasAProductionCallSiteOutsideTheAuditPackage() throws IOException {
        Path engineMain = PROJECT_ROOT.resolve("engine").resolve("src/main/java");
        assertTrue(Files.isDirectory(engineMain), "engine main sources must exist: " + engineMain);
        List<String> qaCallers = new ArrayList<>();
        List<String> auditorNews = new ArrayList<>();
        try (Stream<Path> files = Files.walk(engineMain)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/requirement/audit/"))
                    .forEach(path -> collect(path, qaCallers, auditorNews));
        }
        assertFalse(auditorNews.isEmpty(),
                "production code outside audit/ must construct DeterministicAuditor:\n"
                        + String.join("\n", auditorNews));
        assertFalse(qaCallers.isEmpty(),
                "production code outside audit/ must call auditQa or auditQaWithClaims "
                        + "(recordClaims-only is the SHADOW false-complete hole):\n"
                        + String.join("\n", qaCallers));
        assertTrue(
                qaCallers.stream().anyMatch(line -> line.contains("RequirementDeliveryEngine.java")),
                "RequirementDeliveryEngine must be a QA audit production caller, found:\n"
                        + String.join("\n", qaCallers));
    }

    private static void collect(Path path, List<String> qaCallers, List<String> auditorNews) {
        String relative = PROJECT_ROOT.relativize(path).toString().replace('\\', '/');
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException exception) {
            qaCallers.add(relative + " -> unreadable: " + exception.getMessage());
            return;
        }
        String[] lines = content.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.contains("new DeterministicAuditor")) {
                auditorNews.add(relative + ":" + (index + 1) + " " + line.strip());
            }
            if (line.contains(".auditQa(") || line.contains(".auditQaWithClaims(")) {
                qaCallers.add(relative + ":" + (index + 1) + " " + line.strip());
            }
        }
    }
}
