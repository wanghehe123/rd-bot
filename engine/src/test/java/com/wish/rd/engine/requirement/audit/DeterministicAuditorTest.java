package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicAuditorTest {

    private final AuditedTaskStateCodec codec = new AuditedTaskStateCodec();
    private final AuditedTaskStateInitializer initializer = new AuditedTaskStateInitializer(codec);
    private final DeterministicAuditor auditor = new DeterministicAuditor(codec);
    private final InMemoryEvidenceRefResolver resolver = new InMemoryEvidenceRefResolver();

    @Test
    void hostVerifySucceededCompletesBuildAndStaticGates() {
        EvidenceRef build = hostRef("build-log");
        EvidenceRef stat = hostRef("static-log");
        resolver.put(build);
        resolver.put(stat);
        AuditMutation mutation = auditor.auditHostVerify(
                initial(),
                new HostVerifySubject(
                        "audit-hv-1", "cmd-hv-1", "coding-1",
                        HostVerificationStatus.SUCCEEDED, false,
                        List.of(build), List.of(stat), 10L),
                resolver);
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("GATE-BUILD").status());
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("GATE-STATIC").status());
        assertEquals("host-verification://artifacts/build-log",
                mutation.nextState().record("GATE-BUILD").evidenceRefs().getFirst().uri());
        assertEquals(AuditCompletion.INCOMPLETE, mutation.auditRun().completion());
        assertEquals(AuditIntegrity.CLEAN, mutation.auditRun().integrity());
    }

    @Test
    void hostVerifyDocsOnlySkipCompletesBothGates() {
        EvidenceRef docs = hostRef("docs-only");
        resolver.put(docs);
        AuditMutation mutation = auditor.auditHostVerify(
                initial(),
                new HostVerifySubject(
                        "audit-hv-2", "cmd-hv-2", "coding-1",
                        HostVerificationStatus.SKIPPED_DOCS_ONLY, true,
                        List.of(docs), List.of(docs), 11L),
                resolver);
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("GATE-BUILD").status());
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("GATE-STATIC").status());
        assertEquals(EvidenceSourceKind.HOST_VERIFICATION,
                mutation.nextState().record("GATE-BUILD").evidenceRefs().getFirst().sourceKind());
    }

    @Test
    void hostVerifyFailureKeepsGatesPending() {
        AuditMutation mutation = auditor.auditHostVerify(
                initial(),
                new HostVerifySubject(
                        "audit-hv-3", "cmd-hv-3", "coding-1",
                        HostVerificationStatus.FAILED_RETRYABLE, false,
                        List.of(), List.of(), 12L),
                resolver);
        assertEquals(AuditedRecordStatus.PENDING, mutation.nextState().record("GATE-BUILD").status());
        assertEquals(AuditCompletion.INCOMPLETE, mutation.auditRun().completion());
    }

    @Test
    void hostVerifyFailureDemotesPreviousCompletedGate() {
        EvidenceRef build = hostRef("build-ok");
        resolver.put(build);
        AuditedTaskState completed = auditor.auditHostVerify(
                initial(),
                new HostVerifySubject(
                        "audit-hv-4", "cmd-hv-4", "coding-1",
                        HostVerificationStatus.SUCCEEDED, false,
                        List.of(build), List.of(build), 13L),
                resolver).nextState();
        AuditMutation demoted = auditor.auditHostVerify(
                completed,
                new HostVerifySubject(
                        "audit-hv-5", "cmd-hv-5", "coding-2",
                        HostVerificationStatus.FAILED_RETRYABLE, false,
                        List.of(), List.of(), 14L),
                resolver);
        assertEquals(AuditedRecordStatus.PENDING, demoted.nextState().record("GATE-BUILD").status());
        assertTrue(demoted.auditRun().untrusted().contains("GATE-BUILD"));
    }

    @Test
    void qaCurrentPassedPromotesMatchingAcceptanceIds() {
        EvidenceRef evidence = qaRef("log-1");
        resolver.put(evidence);
        registerFingerprint();
        AuditedTaskState afterBuild = succeedHostVerify(initial());
        AuditMutation mutation = auditor.auditQa(
                afterBuild,
                qaSubject(true, fingerprints("aaa", "aaa"), List.of(
                        new QaCurrentAcceptance("AC-001", "登录成功", "PASSED", 0, List.of(evidence)),
                        new QaCurrentAcceptance("AC-002", "下单成功", "PASSED", 0, List.of(evidence)))),
                resolver);
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("AC-001").status());
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("AC-002").status());
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("GATE-QA-EVIDENCE").status());
        assertEquals(AuditCompletion.COMPLETE, mutation.auditRun().completion());
        assertEquals(AuditIntegrity.CLEAN, mutation.auditRun().integrity());
    }

    @Test
    void qaMissingCoverageStaysIncomplete() {
        EvidenceRef evidence = qaRef("log-2");
        resolver.put(evidence);
        registerFingerprint();
        AuditMutation mutation = auditor.auditQa(
                succeedHostVerify(initial()),
                qaSubject(true, fingerprints("aaa", "aaa"), List.of(
                        new QaCurrentAcceptance("AC-001", "登录成功", "PASSED", 0, List.of(evidence)))),
                resolver);
        assertEquals(AuditedRecordStatus.PENDING, mutation.nextState().record("AC-002").status());
        assertEquals(AuditCompletion.INCOMPLETE, mutation.auditRun().completion());
        assertTrue(mutation.auditRun().missing().contains("AC-002"));
    }

    @Test
    void qaPassedClaimWithoutAuditDoesNotPromote() {
        AuditedTaskState afterClaims = auditor.recordClaims(
                initial(),
                new RoleClaimSubject("audit-c-1", "cmd-c-1", "qa-1", "QA_AGENT",
                        List.of(new RoleClaim("status", "PASSED")), List.of(), 20L)).nextState();
        assertEquals(AuditedRecordStatus.PENDING, afterClaims.record("AC-001").status());
        assertEquals(AuditedRecordStatus.UNTRUSTED, afterClaims.record("CLAIM-qa-1-1").status());
    }

    @Test
    void legacyQaMatchesNormalizedCriteriaText() {
        EvidenceRef evidence = qaRef("log-3");
        resolver.put(evidence);
        registerFingerprint();
        AuditMutation mutation = auditor.auditQa(
                succeedHostVerify(initial()),
                qaSubject(false, fingerprints("aaa", "aaa"), List.of(
                        new QaCurrentAcceptance("", " 登录成功 ", "PASSED", 0, List.of(evidence)),
                        new QaCurrentAcceptance("", "下单成功", "PASSED", 0, List.of(evidence)))),
                resolver);
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("AC-001").status());
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("AC-002").status());
    }

    @Test
    void fingerprintMismatchBlocksAndDoesNotPromote() {
        EvidenceRef evidence = qaRef("log-4");
        resolver.put(evidence);
        AuditMutation mutation = auditor.auditQa(
                succeedHostVerify(initial()),
                qaSubject(true, fingerprints("before", "after"), List.of(
                        new QaCurrentAcceptance("AC-001", "登录成功", "PASSED", 0, List.of(evidence)),
                        new QaCurrentAcceptance("AC-002", "下单成功", "PASSED", 0, List.of(evidence)))),
                resolver);
        assertEquals(AuditIntegrity.VIOLATION, mutation.auditRun().integrity());
        assertEquals(AuditedRecordStatus.BLOCKED, mutation.nextState().record("GATE-WORKSPACE-INTEGRITY").status());
        assertEquals(AuditedRecordStatus.PENDING, mutation.nextState().record("AC-001").status());
        assertEquals(AuditCompletion.BLOCKED, mutation.auditRun().completion());
    }

    @Test
    void missingFingerprintKeysAreSuspect() {
        AuditMutation mutation = auditor.auditQa(
                succeedHostVerify(initial()),
                new QaSubject(
                        "audit-qa-s", "cmd-qa-s", "qa-1", true, null, null, List.of()),
                resolver);
        assertEquals(AuditIntegrity.SUSPECT, mutation.auditRun().integrity());
        assertEquals(AuditedRecordStatus.PENDING, mutation.nextState().record("AC-001").status());
    }

    @Test
    void matchingFingerprintsCompleteIntegrityGate() {
        EvidenceRef fingerprint = new EvidenceRef(
                "audit-qa-clean",
                EvidenceSourceKind.WORKSPACE_FINGERPRINT,
                "workspace-fingerprint://task-1/qa-1",
                "sha256:" + "d".repeat(64));
        resolver.put(fingerprint);
        AuditMutation mutation = auditor.auditQa(
                succeedHostVerify(initial()),
                qaSubject(true, fingerprints("same", "same"), List.of()),
                resolver);
        assertEquals(AuditIntegrity.CLEAN, mutation.auditRun().integrity());
        assertEquals(AuditedRecordStatus.COMPLETED, mutation.nextState().record("GATE-WORKSPACE-INTEGRITY").status());
    }

    @Test
    void claimsAreUntrustedAndBoundedTo32() {
        List<RoleClaim> claims = new ArrayList<>();
        for (int index = 1; index <= 33; index++) {
            claims.add(new RoleClaim("fact-" + index, "value-" + index));
        }
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> auditor.recordClaims(initial(), new RoleClaimSubject(
                        "audit-c-2", "cmd-c-2", "coding-9", "CODING_AGENT", claims, List.of(), 30L)));
        assertTrue(failure.getMessage().contains("32"));

        AuditMutation mutation = auditor.recordClaims(
                initial(),
                new RoleClaimSubject(
                        "audit-c-3", "cmd-c-3", "coding-9", "CODING_AGENT",
                        List.of(new RoleClaim("testStatus", "PASSED")),
                        List.of(new RoleFact("tests green", "DECLARED")),
                        31L));
        assertEquals("CLAIM-coding-9-1", mutation.nextState().record("CLAIM-coding-9-1").id());
        assertEquals(AuditedRecordStatus.UNTRUSTED, mutation.nextState().record("CLAIM-coding-9-1").status());
        assertEquals(AuditedRecordKind.FACT, mutation.nextState().record("CLAIM-coding-9-2").kind());
        assertEquals(AuditedRecordStatus.PENDING, mutation.nextState().record("GATE-BUILD").status());
    }

    @Test
    void danglingEvidenceRefFailsClosed() {
        EvidenceRef dangling = hostRef("missing");
        assertThrows(IllegalStateException.class, () -> auditor.auditHostVerify(
                initial(),
                new HostVerifySubject(
                        "audit-hv-d", "cmd-hv-d", "coding-1",
                        HostVerificationStatus.SUCCEEDED, false,
                        List.of(dangling), List.of(dangling), 40L),
                resolver));
    }

    private void registerFingerprint() {
        resolver.put(new EvidenceRef(
                "audit-qa-1",
                EvidenceSourceKind.WORKSPACE_FINGERPRINT,
                "workspace-fingerprint://task-1/qa-1",
                "sha256:" + "d".repeat(64)));
    }

    private AuditedTaskState initial() {
        return initializer.initialize(AuditedTaskFixtures.task(
                "[\"登录成功\",\"下单成功\"]", false));
    }

    private AuditedTaskState succeedHostVerify(AuditedTaskState head) {
        EvidenceRef build = hostRef("ok");
        resolver.put(build);
        return auditor.auditHostVerify(
                head,
                new HostVerifySubject(
                        "audit-hv-ok", "cmd-hv-ok-" + head.stateVersion(), "coding-ok",
                        HostVerificationStatus.SUCCEEDED, false,
                        List.of(build), List.of(build), 9L),
                resolver).nextState();
    }

    private static QaSubject qaSubject(
            boolean v2,
            Fingerprints fingerprints,
            List<QaCurrentAcceptance> current
    ) {
        return new QaSubject(
                "audit-qa-1", "cmd-qa-1", "qa-1", v2,
                fingerprints.before(), fingerprints.after(), current);
    }

    private static Fingerprints fingerprints(String before, String after) {
        return new Fingerprints(
                new WorkspaceFingerprintReceipt("head-a", before, 3),
                new WorkspaceFingerprintReceipt(before.equals(after) ? "head-a" : "head-b", after, 3));
    }

    private static EvidenceRef hostRef(String artifactId) {
        return new EvidenceRef(
                "audit-hv",
                EvidenceSourceKind.HOST_VERIFICATION,
                "host-verification://artifacts/" + artifactId,
                "sha256:" + "a".repeat(64));
    }

    private static EvidenceRef qaRef(String artifactId) {
        return new EvidenceRef(
                "audit-qa",
                EvidenceSourceKind.QA_EVIDENCE,
                "qa-evidence://objects/" + artifactId,
                "sha256:" + "b".repeat(64));
    }

    private record Fingerprints(WorkspaceFingerprintReceipt before, WorkspaceFingerprintReceipt after) {
    }
}
