package com.wish.rd.engine.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaRemediationPackageBuilderTest {

    private final QaRemediationPackageBuilder builder = new QaRemediationPackageBuilder();

    @Test
    void shouldBuildBoundedHashBoundAttachmentAndFindingTodos() {
        QaRemediationPackageBuilder.Package value = builder.build("qa-stage-1", 1, """
                {
                  "status":"FAILED",
                  "remediationRequest":{"requested":true,"targetRole":"CODING_AGENT",
                    "reason":"Fix the verified login regression","bugFindingIds":["BUG-1"]},
                  "bugFindings":[{"id":"BUG-1","severity":"HIGH","acceptanceCriteriaId":"AC-1",
                    "reproductionSteps":["Open login","Submit a valid account"],
                    "expected":"Dashboard opens","actual":"HTTP 500 is shown",
                    "evidenceArtifactIds":["qa-evidence/network/login.json"],
                    "suspectedFiles":["src/auth/LoginService.java"]}]
                }
                """);

        assertEquals("attachments/qa-remediation/request.json", value.attachment().path());
        assertTrue(value.attachment().hash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(value.attachment().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                value.attachment().bytes());
        assertTrue(value.promptSection().contains("/work/input/attachments/qa-remediation/request.json"));
        assertTrue(value.promptSection().contains("BUG-1"));
        assertTrue(value.promptSection().contains("qa-evidence/network/login.json"));
        assertEquals(1, value.todos().size());
        assertTrue(value.todos().getFirst().contains("AC-1"));
    }

    @Test
    void shouldRejectSecretsExternalUrlsUnsafePathsAndOversizedPayloads() {
        String template = """
                {"status":"FAILED","remediationRequest":{"requested":true,"targetRole":"CODING_AGENT",
                "reason":"%s","bugFindingIds":["BUG-1"]},"bugFindings":[{"id":"BUG-1",
                "severity":"HIGH","acceptanceCriteriaId":"AC-1","reproductionSteps":["repro"],
                "expected":"ok","actual":"bad","evidenceArtifactIds":["%s"],"suspectedFiles":["%s"]}]}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("qa", 1, template.formatted("Bearer secret-token", "qa-evidence/a", "src/A.java")));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("qa", 1, template.formatted("see https://evil.test", "qa-evidence/a", "src/A.java")));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("qa", 1, template.formatted("fix", "../private.log", "src/A.java")));
        assertThrows(IllegalArgumentException.class,
                () -> builder.build("qa", 1, template.formatted("x".repeat(70_000), "qa-evidence/a", "src/A.java")));
    }

    @Test
    void shouldRehydrateFrozenCanonicalAndNonCanonicalJsonWhenHashMatches() {
        QaRemediationPackageBuilder.Package original = builder.build("qa-stage-1", 1, """
                {
                  "status":"FAILED",
                  "remediationRequest":{"requested":true,"targetRole":"CODING_AGENT",
                    "reason":"Fix the verified login regression","bugFindingIds":["BUG-1"]},
                  "bugFindings":[{"id":"BUG-1","severity":"HIGH","acceptanceCriteriaId":"AC-1",
                    "reproductionSteps":["Open login"],
                    "expected":"Dashboard opens","actual":"HTTP 500 is shown",
                    "evidenceArtifactIds":["qa-evidence/network/login.json"],
                    "suspectedFiles":["src/auth/LoginService.java"]}]
                }
                """);
        QaRemediationPackageBuilder.Package frozen = builder.fromFrozen(
                original.attachment().content(), original.requestHash());
        assertEquals(original.requestHash(), frozen.requestHash());
        assertEquals(original.attachment().content(), frozen.attachment().content());
        assertTrue(frozen.promptSection().contains("BUG-1"));

        String pretty = original.attachment().content()
                .replace("{", "{\n")
                .replace(",", ",\n")
                .replace("}", "\n}");
        QaRemediationPackageBuilder.Package roundTripped = builder.fromFrozen(pretty, original.requestHash());
        assertEquals(original.attachment().content(), roundTripped.attachment().content());
        assertThrows(IllegalArgumentException.class,
                () -> builder.fromFrozen(pretty, "sha256:" + "0".repeat(64)));
    }
}
