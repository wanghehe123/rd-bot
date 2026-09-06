package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaSubjectExtractorTest {

    private static final String SHA = "sha256:" + "b".repeat(64);

    @Test
    void currentPassedWithCriteriaIdBecomesOneAcceptance() {
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-1",
                "cmd-1",
                "qa-1",
                true,
                """
                        {
                          "status": "PASSED",
                          "acceptanceResults": [
                            {
                              "criteriaId": "AC-001",
                              "criteria": "client 生产构建通过",
                              "scope": "CURRENT",
                              "status": "PASSED",
                              "exitCode": 0,
                              "logArtifactId": "qa-evidence/console/ac001.log",
                              "evidenceArtifactIds": ["qa-evidence/console/ac001.log"]
                            }
                          ],
                          "dockerMetadata": {
                            "qaWorkspaceFingerprintBeforeJson": "{\\"headSha\\":\\"abc\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}",
                            "qaWorkspaceFingerprintAfterJson": "{\\"headSha\\":\\"abc\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}",
                            "qaWorkspaceIntegrity": "CLEAN"
                          }
                        }
                        """,
                path -> Optional.empty());
        assertEquals(1, subject.currentAcceptances().size());
        QaCurrentAcceptance hit = subject.currentAcceptances().getFirst();
        assertEquals("AC-001", hit.criteriaId());
        assertEquals("PASSED", hit.status());
        assertEquals(0, hit.exitCode());
        assertEquals("qa-evidence/console/ac001.log", hit.evidenceRefs().getFirst().uri());
        assertEquals(EvidenceSourceKind.QA_EVIDENCE, hit.evidenceRefs().getFirst().sourceKind());
        assertEquals("abc", subject.fingerprintBefore().headSha());
        assertEquals("sha256:aaa", subject.fingerprintBefore().trackedTreeSha256());
        assertEquals(3, subject.fingerprintBefore().trackedFileCount());
        assertEquals(subject.fingerprintBefore(), subject.fingerprintAfter());
    }

    @Test
    void regressionRowsAreIgnoredForPromotion() {
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-2",
                "cmd-2",
                "qa-1",
                true,
                """
                        {
                          "acceptanceResults": [
                            {
                              "criteriaId": "AC-001",
                              "criteria": "current",
                              "scope": "CURRENT",
                              "status": "PASSED",
                              "exitCode": 0,
                              "logArtifactId": "qa-evidence/console/ac001.log",
                              "evidenceArtifactIds": ["qa-evidence/console/ac001.log"]
                            },
                            {
                              "criteriaId": "AC-002",
                              "criteria": "regression",
                              "scope": "REGRESSION",
                              "status": "PASSED",
                              "exitCode": 0,
                              "logArtifactId": "qa-evidence/console/reg.log",
                              "evidenceArtifactIds": ["qa-evidence/console/reg.log"]
                            }
                          ]
                        }
                        """,
                path -> Optional.empty());
        assertEquals(1, subject.currentAcceptances().size());
        assertEquals("AC-001", subject.currentAcceptances().getFirst().criteriaId());
    }

    @Test
    void missingFingerprintsStayNull() {
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-3",
                "cmd-3",
                "qa-1",
                true,
                "{\"acceptanceResults\":[]}",
                path -> Optional.empty());
        assertNull(subject.fingerprintBefore());
        assertNull(subject.fingerprintAfter());
    }

    @Test
    void mismatchedFingerprintsAreTwoReceipts() {
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-4",
                "cmd-4",
                "qa-1",
                true,
                """
                        {
                          "dockerMetadata": {
                            "qaWorkspaceFingerprintBeforeJson": "{\\"headSha\\":\\"before\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}",
                            "qaWorkspaceFingerprintAfterJson": "{\\"headSha\\":\\"after\\",\\"trackedTreeSha256\\":\\"sha256:bbb\\",\\"trackedFileCount\\":4}"
                          }
                        }
                        """,
                path -> Optional.empty());
        assertEquals("before", subject.fingerprintBefore().headSha());
        assertEquals("after", subject.fingerprintAfter().headSha());
        assertEquals("sha256:aaa", subject.fingerprintBefore().trackedTreeSha256());
        assertEquals("sha256:bbb", subject.fingerprintAfter().trackedTreeSha256());
        assertEquals(3, subject.fingerprintBefore().trackedFileCount());
        assertEquals(4, subject.fingerprintAfter().trackedFileCount());
    }

    @Test
    void persistedObjectUriIsPreferredOverArtifactPath() {
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-5",
                "cmd-5",
                "qa-1",
                true,
                """
                        {
                          "acceptanceResults": [
                            {
                              "criteriaId": "AC-001",
                              "criteria": "build",
                              "scope": "CURRENT",
                              "status": "PASSED",
                              "exitCode": 0,
                              "logArtifactId": "qa-evidence/console/ac001.log",
                              "evidenceArtifactIds": ["qa-evidence/console/ac001.log"]
                            }
                          ]
                        }
                        """,
                path -> "qa-evidence/console/ac001.log".equals(path)
                        ? Optional.of(new QaEvidenceLookup.PersistedObject("42", SHA))
                        : Optional.empty());
        assertEquals("qa-evidence://objects/42", subject.currentAcceptances().getFirst().evidenceRefs().getFirst().uri());
        assertEquals(SHA, subject.currentAcceptances().getFirst().evidenceRefs().getFirst().sha256());
    }

    @Test
    void nestedAggregateEnvelopeUsesAuthoritativeQaResultJson() throws Exception {
        String inner = """
                {
                  "status": "PASSED",
                  "acceptanceResults": [
                    {
                      "criteriaId": "AC-001",
                      "criteria": "build",
                      "scope": "CURRENT",
                      "status": "PASSED",
                      "exitCode": 0,
                      "logArtifactId": "qa-evidence/console/ac001.log",
                      "evidenceArtifactIds": ["qa-evidence/console/ac001.log"]
                    }
                  ],
                  "dockerMetadata": {
                    "qaWorkspaceFingerprintBeforeJson": "{\\"headSha\\":\\"abc\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}",
                    "qaWorkspaceFingerprintAfterJson": "{\\"headSha\\":\\"abc\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}"
                  }
                }
                """;
        String envelope = new ObjectMapper().writeValueAsString(Map.of(
                "stages", List.of(Map.of("role", "QA_AGENT", "resultJson", inner))));
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-6", "cmd-6", "qa-1", true, envelope, path -> Optional.empty());
        assertEquals(1, subject.currentAcceptances().size());
        assertEquals("AC-001", subject.currentAcceptances().getFirst().criteriaId());
        assertEquals("abc", subject.fingerprintBefore().headSha());
        assertTrue(subject.currentAcceptances().getFirst().evidenceRefs().getFirst().uri()
                .contains("qa-evidence/console/ac001.log"));
    }

    @Test
    void codingMergeEnvelopeReadsFingerprintsFromMultiAgentStages() throws Exception {
        String inner = """
                {
                  "status": "PASSED",
                  "acceptanceResults": [
                    {
                      "criteriaId": "AC-001",
                      "criteria": "build",
                      "scope": "CURRENT",
                      "status": "PASSED",
                      "exitCode": 0,
                      "logArtifactId": "qa-evidence/console/ac001.log",
                      "evidenceArtifactIds": ["qa-evidence/console/ac001.log"]
                    }
                  ],
                  "dockerMetadata": {
                    "qaWorkspaceFingerprintBeforeJson": "{\\"headSha\\":\\"abc\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}",
                    "qaWorkspaceFingerprintAfterJson": "{\\"headSha\\":\\"abc\\",\\"trackedTreeSha256\\":\\"sha256:aaa\\",\\"trackedFileCount\\":3}"
                  }
                }
                """;
        String envelope = new ObjectMapper().writeValueAsString(Map.of(
                "status", "SUCCESS",
                "testStatus", "PASSED",
                "changedFiles", List.of("client/src/pages/customer/Home.tsx"),
                "multiAgentStages", List.of(
                        Map.of("role", "CODING_AGENT", "resultJson", "{\"status\":\"PASSED\"}"),
                        Map.of("role", "QA_AGENT", "success", true, "resultJson", inner))));
        QaSubject subject = QaSubjectExtractor.fromResultJson(
                "audit-7", "cmd-7", "qa-1", true, envelope, path -> Optional.empty());
        assertEquals(1, subject.currentAcceptances().size());
        assertEquals("AC-001", subject.currentAcceptances().getFirst().criteriaId());
        assertEquals("abc", subject.fingerprintBefore().headSha());
        assertEquals("sha256:aaa", subject.fingerprintAfter().trackedTreeSha256());
    }
}
