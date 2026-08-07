package com.wish.rd.exec.repair.result;

import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaEvidenceBundleValidatorTest {

    private final QaEvidenceBundleValidator validator = new QaEvidenceBundleValidator();

    @Test
    void shouldRejectLogicalEvidenceReferencesWithoutCollectedArtifacts() {
        AgentRoleResultValidation validation = validator.validate(strictQaResult(), List.of(
                manifestArtifact(List.of())
        ));

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "acceptanceResults[0].logArtifactId does not resolve to a collected artifact: qa-evidence/commands/current.log"));
        assertTrue(validation.errors().contains(
                "acceptanceResults[0].evidenceArtifactIds[0] does not resolve to a collected artifact: "
                        + "qa-evidence/screenshots/current-desktop.png"));
    }

    @Test
    void shouldAcceptBrowserEvidenceWhenEveryReferenceHasBytesAndHash() {
        AgentRoleResultValidation validation = validator.validate(strictQaResult(), browserArtifacts());

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldRejectBrowserEvidenceWithoutConsoleAndNetworkRecords() {
        List<RepairArtifact> evidence = List.of(
                artifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/current.log", "current log"),
                artifact(RepairArtifactType.QA_SCREENSHOT, "qa-evidence/screenshots/current.png", "screenshot"),
                artifact(RepairArtifactType.QA_TRACE, "qa-evidence/traces/current.zip", "trace"),
                artifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/regression.log", "regression log")
        );
        List<RepairArtifact> artifacts = new ArrayList<>(evidence);
        artifacts.add(0, manifestArtifact(evidence));

        AgentRoleResultValidation validation = validator.validate(strictQaResult(), artifacts);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "browser validation requires referenced QA_CONSOLE_LOG evidence"));
        assertTrue(validation.errors().contains(
                "browser validation requires referenced QA_NETWORK_LOG evidence"));
    }

    @Test
    void shouldRejectManifestThatDoesNotCoverEveryCollectedEvidenceArtifact() {
        List<RepairArtifact> evidence = browserEvidenceArtifacts();
        List<RepairArtifact> artifacts = new ArrayList<>(evidence);
        artifacts.add(0, manifestArtifact(evidence.stream()
                .filter(artifact -> artifact.type() != RepairArtifactType.QA_TRACE)
                .toList()));

        AgentRoleResultValidation validation = validator.validate(strictQaResult(), artifacts);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "QA evidence manifest does not cover collected artifact: qa-evidence/traces/current.zip"));
    }

    @Test
    void shouldRejectBrowserQaThatClaimsBothViewportsWithOnlyOneScreenshot() {
        AgentRoleResultValidation validation = validator.validate(
                strictQaResult(),
                singleViewportBrowserArtifacts()
        );

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "browser validation requires referenced mobile-390x844 screenshot evidence"));
    }

    @Test
    void shouldRejectQaReportThatDoesNotMapEveryTaskCriterionToCurrentEvidence() {
        AgentRoleResultValidation validation = validator.validate(
                strictQaResult(),
                browserArtifacts(),
                List.of("current feature", "second current feature")
        );

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "evidence is missing task acceptance criterion: second current feature"));
    }

    @Test
    void shouldAcceptTaskCriteriaWhenQaPrefixesAcLabels() {
        AgentRoleResultValidation validation = validator.validate(
                strictQaResultWithPrefixedCriteria(),
                browserArtifacts(),
                List.of("current feature", "critical regression")
        );

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void shouldRejectPassedBrowserCommandLogContainingPlaywrightErrors() {
        List<RepairArtifact> evidence = browserEvidenceArtifacts().stream()
                .map(existing -> existing.name().equals("qa-evidence/commands/current.log")
                        ? artifact(
                                RepairArtifactType.QA_COMMAND_LOG,
                                existing.name(),
                                "exitCode=0\n{\"isError\": true, \"error\": \"locator.click timed out\"}"
                        )
                        : existing)
                .toList();
        List<RepairArtifact> artifacts = new ArrayList<>(evidence);
        artifacts.add(0, manifestArtifact(evidence));

        AgentRoleResultValidation validation = validator.validate(strictQaResult(), artifacts);

        assertFalse(validation.valid());
        assertTrue(validation.errors().contains(
                "acceptanceResults[0] claims PASSED but its command log contains a Playwright isError response"));
    }

    private static List<RepairArtifact> browserArtifacts() {
        List<RepairArtifact> evidence = browserEvidenceArtifacts();
        List<RepairArtifact> artifacts = new ArrayList<>(evidence);
        artifacts.add(0, manifestArtifact(evidence));
        return List.copyOf(artifacts);
    }

    private static List<RepairArtifact> singleViewportBrowserArtifacts() {
        List<RepairArtifact> evidence = List.of(
                artifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/current.log", "current log"),
                artifact(RepairArtifactType.QA_SCREENSHOT,
                        "qa-evidence/screenshots/current-desktop.png", "desktop screenshot"),
                artifact(RepairArtifactType.QA_TRACE, "qa-evidence/traces/current.zip", "trace"),
                artifact(RepairArtifactType.QA_CONSOLE_LOG, "qa-evidence/console/current.log", "no console errors"),
                artifact(RepairArtifactType.QA_NETWORK_LOG, "qa-evidence/network/current.log", "GET / 200"),
                artifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/regression.log", "regression log")
        );
        List<RepairArtifact> artifacts = new ArrayList<>(evidence);
        artifacts.add(0, manifestArtifact(evidence));
        return List.copyOf(artifacts);
    }

    private static List<RepairArtifact> browserEvidenceArtifacts() {
        return List.of(
                artifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/current.log", "current log"),
                artifact(RepairArtifactType.QA_SCREENSHOT,
                        "qa-evidence/screenshots/current-desktop.png", "desktop screenshot"),
                artifact(RepairArtifactType.QA_SCREENSHOT,
                        "qa-evidence/screenshots/current-mobile.png", "mobile screenshot"),
                artifact(RepairArtifactType.QA_TRACE, "qa-evidence/traces/current.zip", "trace"),
                artifact(RepairArtifactType.QA_CONSOLE_LOG, "qa-evidence/console/current.log", "no console errors"),
                artifact(RepairArtifactType.QA_NETWORK_LOG, "qa-evidence/network/current.log", "GET / 200"),
                artifact(RepairArtifactType.QA_COMMAND_LOG, "qa-evidence/commands/regression.log", "regression log")
        );
    }

    private static RepairArtifact manifestArtifact(List<RepairArtifact> evidence) {
        String entries = evidence.stream()
                .map(artifact -> """
                        {"path":"%s","bytes":%s,"sha256":"%s"}
                        """.formatted(
                                artifact.name(),
                                artifact.metadataJson().get("bytes"),
                                artifact.metadataJson().get("sha256")
                        ).strip())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return artifact(
                RepairArtifactType.QA_EVIDENCE_MANIFEST,
                "qa-evidence/manifest.json",
                "{\"version\":1,\"artifacts\":[" + entries + "]}"
        );
    }

    private static RepairArtifact artifact(RepairArtifactType type, String name, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new RepairArtifact(
                type,
                name,
                "file:///tmp/output/" + name,
                "QA evidence",
                Map.of(
                        "bytes", String.valueOf(bytes.length),
                        "sha256", sha256(bytes),
                        "contentType", "application/octet-stream",
                        "contentPreview", body
                )
        );
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String strictQaResult() {
        return """
                {
                  "status": "PASSED",
                  "summary": "current and regression browser checks passed",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": true,
                    "performed": true,
                    "decisionSource": "AUTO_DETECTION",
                    "baseUrl": "http://127.0.0.1:4173",
                    "browser": "chromium",
                    "viewports": ["desktop-1440x900", "mobile-390x844"]
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "current feature",
                      "scope": "CURRENT",
                      "command": "playwright-cli screenshot",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 1200,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": [
                        "qa-evidence/screenshots/current-desktop.png",
                        "qa-evidence/screenshots/current-mobile.png",
                        "qa-evidence/traces/current.zip",
                        "qa-evidence/console/current.log",
                        "qa-evidence/network/current.log"
                      ]
                    },
                    {
                      "criteria": "critical regression",
                      "scope": "REGRESSION",
                      "command": "npm test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 800,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """;
    }

    private static String strictQaResultWithPrefixedCriteria() {
        return strictQaResult()
                .replace("\"criteria\": \"current feature\"", "\"criteria\": \"AC1: current feature\"")
                .replace("\"criteria\": \"critical regression\"", "\"criteria\": \"AC2: critical regression\"");
    }
}
