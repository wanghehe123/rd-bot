package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Host-owned pure auditor. Executor JSON never promotes blocking records. */
public final class DeterministicAuditor {

    private final AuditedTaskStateCodec codec;

    public DeterministicAuditor(AuditedTaskStateCodec codec) {
        this.codec = codec == null ? new AuditedTaskStateCodec() : codec;
    }

    /**
     * Applies a host BUILD/STATIC verification run.
     *
     * @param head current state
     * @param subject verification evidence
     * @param resolver evidence URI resolver
     * @return mutation
     */
    public AuditMutation auditHostVerify(
            AuditedTaskState head,
            HostVerifySubject subject,
            EvidenceRefResolverPort resolver
    ) {
        requireHead(head);
        if (subject == null) {
            throw new IllegalArgumentException("host verify subject must not be null");
        }
        List<AuditedRecord> records = new ArrayList<>(head.records());
        List<String> verified = new ArrayList<>();
        List<String> untrusted = new ArrayList<>();
        boolean success = subject.status() == HostVerificationStatus.SUCCEEDED
                || subject.status() == HostVerificationStatus.SKIPPED_DOCS_ONLY;
        if (success) {
            completeGate(records, "GATE-BUILD", subject.auditRunId(), subject.buildEvidence(), resolver, verified);
            completeGate(records, "GATE-STATIC", subject.auditRunId(), subject.staticEvidence(), resolver, verified);
        } else {
            demoteIfCompleted(records, "GATE-BUILD", untrusted);
            demoteIfCompleted(records, "GATE-STATIC", untrusted);
        }
        return finish(head, subject.auditRunId(), subject.commandId(), subject.codingStageRunId(),
                "HOST_VERIFY", records, verified, untrusted, List.of(), AuditIntegrity.CLEAN,
                subject.nowEpochMillis());
    }

    /**
     * Applies QA evidence and workspace fingerprints.
     *
     * @param head current state
     * @param subject QA evidence
     * @param resolver evidence URI resolver
     * @return mutation
     */
    public AuditMutation auditQa(
            AuditedTaskState head,
            QaSubject subject,
            EvidenceRefResolverPort resolver
    ) {
        requireHead(head);
        if (subject == null) {
            throw new IllegalArgumentException("qa subject must not be null");
        }
        return finishQa(
                head,
                subject,
                new ArrayList<>(head.records()),
                new ArrayList<>(),
                resolver,
                0L);
    }

    /**
     * Records UNTRUSTED claims and applies QA evidence in one revision.
     *
     * @param head current state
     * @param qaSubject QA evidence
     * @param claims executor self-reports
     * @param resolver evidence URI resolver
     * @return mutation
     */
    public AuditMutation auditQaWithClaims(
            AuditedTaskState head,
            QaSubject qaSubject,
            RoleClaimSubject claims,
            EvidenceRefResolverPort resolver
    ) {
        requireHead(head);
        if (qaSubject == null) {
            throw new IllegalArgumentException("qa subject must not be null");
        }
        List<AuditedRecord> records = new ArrayList<>(head.records());
        List<String> untrusted = new ArrayList<>();
        appendUntrustedClaims(records, claims, untrusted);
        return finishQa(head, qaSubject, records, untrusted, resolver, claims.nowEpochMillis());
    }

    /**
     * Records executor self-reports as {@code UNTRUSTED} facts without promoting gates.
     *
     * @param head current state
     * @param subject claims
     * @return mutation
     */
    public AuditMutation recordClaims(AuditedTaskState head, RoleClaimSubject subject) {
        requireHead(head);
        List<AuditedRecord> records = new ArrayList<>(head.records());
        List<String> untrusted = new ArrayList<>();
        appendUntrustedClaims(records, subject, untrusted);
        return finish(head, subject.auditRunId(), subject.commandId(), subject.stageRunId(),
                subject.role(), records, List.of(), untrusted, List.of(), AuditIntegrity.CLEAN,
                subject.nowEpochMillis());
    }

    /**
     * Host fingerprint evidence URI for a QA subject. Callers must register this before
     * {@link #auditQa} / {@link #auditQaWithClaims} so the resolver can fail closed.
     *
     * @param taskId owning task
     * @param subject QA subject with after-fingerprint
     * @return fingerprint evidence ref
     */
    public static EvidenceRef qaWorkspaceFingerprintRef(String taskId, QaSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("qa subject must not be null");
        }
        return fingerprintEvidence(taskId, subject);
    }

    private AuditMutation finishQa(
            AuditedTaskState head,
            QaSubject subject,
            List<AuditedRecord> records,
            List<String> untrusted,
            EvidenceRefResolverPort resolver,
            long nowEpochMillis
    ) {
        List<String> verified = new ArrayList<>();
        AuditIntegrity integrity = integrityOf(subject);
        if (integrity == AuditIntegrity.VIOLATION) {
            replace(records, "GATE-WORKSPACE-INTEGRITY", head.record("GATE-WORKSPACE-INTEGRITY")
                    .withStatus(AuditedRecordStatus.BLOCKED, List.of(), "workspace integrity violation"));
            return finish(head, subject.auditRunId(), subject.commandId(), subject.qaStageRunId(),
                    "QA_AGENT", records, verified, untrusted, List.of("GATE-WORKSPACE-INTEGRITY"),
                    integrity, nowEpochMillis);
        }
        if (integrity == AuditIntegrity.SUSPECT) {
            return finish(head, subject.auditRunId(), subject.commandId(), subject.qaStageRunId(),
                    "QA_AGENT", records, verified, untrusted, List.of(), integrity, nowEpochMillis);
        }
        EvidenceRef fingerprintRef = fingerprintEvidence(head.taskId(), subject);
        resolver.requireResolvable(fingerprintRef);
        replace(records, "GATE-WORKSPACE-INTEGRITY", head.record("GATE-WORKSPACE-INTEGRITY")
                .withStatus(AuditedRecordStatus.COMPLETED, List.of(fingerprintRef), ""));
        verified.add("GATE-WORKSPACE-INTEGRITY");
        for (AuditedRecord record : List.copyOf(records)) {
            if (record.kind() != AuditedRecordKind.REQUIREMENT) {
                continue;
            }
            QaCurrentAcceptance hit = findAcceptance(subject, record);
            if (hit == null || !"PASSED".equalsIgnoreCase(hit.status()) || hit.exitCode() != 0) {
                continue;
            }
            hit.evidenceRefs().forEach(resolver::requireResolvable);
            if (hit.evidenceRefs().isEmpty()) {
                continue;
            }
            replace(records, record.id(), record.withStatus(
                    AuditedRecordStatus.COMPLETED, stamp(hit.evidenceRefs(), subject.auditRunId()), ""));
            verified.add(record.id());
        }
        boolean allRequirementsComplete = records.stream()
                .filter(record -> record.kind() == AuditedRecordKind.REQUIREMENT)
                .allMatch(record -> record.status() == AuditedRecordStatus.COMPLETED);
        if (allRequirementsComplete) {
            List<EvidenceRef> qaEvidence = subject.currentAcceptances().stream()
                    .flatMap(hit -> hit.evidenceRefs().stream())
                    .toList();
            if (!qaEvidence.isEmpty()) {
                qaEvidence.forEach(resolver::requireResolvable);
                replace(records, "GATE-QA-EVIDENCE", head.record("GATE-QA-EVIDENCE")
                        .withStatus(AuditedRecordStatus.COMPLETED, stamp(qaEvidence, subject.auditRunId()), ""));
                verified.add("GATE-QA-EVIDENCE");
            }
        }
        return finish(head, subject.auditRunId(), subject.commandId(), subject.qaStageRunId(),
                "QA_AGENT", records, verified, untrusted, List.of(), integrity, nowEpochMillis);
    }

    private static void appendUntrustedClaims(
            List<AuditedRecord> records,
            RoleClaimSubject subject,
            List<String> untrusted
    ) {
        if (subject == null) {
            throw new IllegalArgumentException("claim subject must not be null");
        }
        int total = subject.claims().size() + subject.facts().size();
        if (total > RoleClaimSubject.MAX_CLAIMS_PER_ATTEMPT) {
            throw new IllegalArgumentException(
                    "claims must not exceed " + RoleClaimSubject.MAX_CLAIMS_PER_ATTEMPT + " per attempt");
        }
        int index = 1;
        for (RoleClaim claim : subject.claims()) {
            String id = "CLAIM-" + subject.stageRunId() + "-" + index++;
            records.add(new AuditedRecord(
                    id, AuditedRecordKind.FACT, false,
                    claim.name() + "=" + claim.value(),
                    AuditedRecordStatus.UNTRUSTED, List.of(), subject.stageRunId(), ""));
            untrusted.add(id);
        }
        for (RoleFact fact : subject.facts()) {
            String id = "CLAIM-" + subject.stageRunId() + "-" + index++;
            records.add(new AuditedRecord(
                    id, AuditedRecordKind.FACT, false,
                    fact.kind() + ":" + fact.statement(),
                    AuditedRecordStatus.UNTRUSTED, List.of(), subject.stageRunId(), ""));
            untrusted.add(id);
        }
    }

    private AuditMutation finish(
            AuditedTaskState head,
            String auditRunId,
            String commandId,
            String subjectStageRunId,
            String subjectRole,
            List<AuditedRecord> records,
            List<String> verified,
            List<String> untrusted,
            List<String> blockers,
            AuditIntegrity integrity,
            long nowEpochMillis
    ) {
        List<String> missing = records.stream()
                .filter(AuditedRecord::blocking)
                .filter(record -> record.status() == AuditedRecordStatus.PENDING)
                .map(AuditedRecord::id)
                .toList();
        List<String> blocked = records.stream()
                .filter(AuditedRecord::blocking)
                .filter(record -> record.status() == AuditedRecordStatus.BLOCKED)
                .map(AuditedRecord::id)
                .toList();
        List<String> allBlockers = new ArrayList<>(new LinkedHashSet<>(concat(blockers, blocked)));
        AuditCompletion completion = !allBlockers.isEmpty() || integrity == AuditIntegrity.VIOLATION
                ? AuditCompletion.BLOCKED
                : (missing.isEmpty() ? AuditCompletion.COMPLETE : AuditCompletion.INCOMPLETE);
        AuditRun run = new AuditRun(
                auditRunId,
                head.taskId(),
                subjectStageRunId,
                subjectRole,
                commandId,
                completion,
                integrity,
                ContractAuditVerdict.ALIGNED,
                truncate(verified, AuditRun.MAX_ID_LIST),
                truncate(missing, AuditRun.MAX_ID_LIST),
                truncate(untrusted, AuditRun.MAX_ID_LIST),
                truncate(allBlockers, AuditRun.MAX_BLOCKERS),
                List.of(),
                Math.max(0L, nowEpochMillis)
        );
        AuditedTaskState next = codec.seal(new AuditedTaskState(
                head.taskId(),
                head.stateVersion() + 1L,
                "",
                head.contractRef(),
                records,
                auditRunId
        ));
        return new AuditMutation(run, next);
    }

    private static void completeGate(
            List<AuditedRecord> records,
            String gateId,
            String auditRunId,
            List<EvidenceRef> evidence,
            EvidenceRefResolverPort resolver,
            List<String> verified
    ) {
        if (evidence.isEmpty()) {
            throw new IllegalStateException("COMPLETED gate requires evidence: " + gateId);
        }
        evidence.forEach(resolver::requireResolvable);
        replace(records, gateId, find(records, gateId)
                .withStatus(AuditedRecordStatus.COMPLETED, stamp(evidence, auditRunId), ""));
        verified.add(gateId);
    }

    private static void demoteIfCompleted(List<AuditedRecord> records, String id, List<String> untrusted) {
        AuditedRecord current = find(records, id);
        if (current.status() == AuditedRecordStatus.COMPLETED) {
            replace(records, id, current.withStatus(AuditedRecordStatus.PENDING, List.of(), ""));
            untrusted.add(id);
        }
    }

    private static QaCurrentAcceptance findAcceptance(QaSubject subject, AuditedRecord record) {
        if (subject.piQaRemediationV2()) {
            return subject.currentAcceptances().stream()
                    .filter(hit -> record.id().equals(hit.criteriaId()))
                    .findFirst()
                    .orElse(null);
        }
        String expected = normalize(record.text());
        return subject.currentAcceptances().stream()
                .filter(hit -> expected.equals(normalize(hit.criteriaText())))
                .findFirst()
                .orElse(null);
    }

    private static AuditIntegrity integrityOf(QaSubject subject) {
        if (subject.fingerprintBefore() == null || subject.fingerprintAfter() == null) {
            return AuditIntegrity.SUSPECT;
        }
        WorkspaceFingerprintReceipt before = subject.fingerprintBefore();
        WorkspaceFingerprintReceipt after = subject.fingerprintAfter();
        if (before.headSha().isBlank() || after.headSha().isBlank()
                || before.trackedTreeSha256().isBlank() || after.trackedTreeSha256().isBlank()) {
            return AuditIntegrity.SUSPECT;
        }
        if (!before.headSha().equals(after.headSha())
                || !before.trackedTreeSha256().equals(after.trackedTreeSha256())) {
            return AuditIntegrity.VIOLATION;
        }
        return AuditIntegrity.CLEAN;
    }

    private static EvidenceRef fingerprintEvidence(String taskId, QaSubject subject) {
        String uri = "workspace-fingerprint://" + taskId + "/" + subject.qaStageRunId();
        String payload = "{\"head\":\"" + subject.fingerprintAfter().headSha()
                + "\",\"tree\":\"" + subject.fingerprintAfter().trackedTreeSha256()
                + "\",\"files\":" + subject.fingerprintAfter().trackedFileCount() + "}";
        return new EvidenceRef(
                subject.auditRunId(),
                EvidenceSourceKind.WORKSPACE_FINGERPRINT,
                uri,
                CanonicalJsonSha256.digest(payload)
        );
    }

    private static List<EvidenceRef> stamp(List<EvidenceRef> evidence, String auditRunId) {
        List<EvidenceRef> stamped = new ArrayList<>();
        for (EvidenceRef ref : boundEvidence(evidence)) {
            stamped.add(new EvidenceRef(auditRunId, ref.sourceKind(), ref.uri(), ref.sha256()));
        }
        return List.copyOf(stamped);
    }

    /**
     * {@link AuditedRecord} fail-closes above 16 refs. Browser QA often attaches a
     * unique console/network/trace/screenshot set per CURRENT row; the GATE-QA-EVIDENCE
     * union then exceeds the bound and used to crash honest ENFORCE tasks.
     */
    private static List<EvidenceRef> boundEvidence(List<EvidenceRef> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        Map<String, EvidenceRef> unique = new LinkedHashMap<>();
        for (EvidenceRef ref : evidence) {
            if (ref != null) {
                unique.putIfAbsent(ref.uri(), ref);
            }
        }
        List<EvidenceRef> ordered = new ArrayList<>(unique.values());
        if (ordered.size() <= AuditedRecord.MAX_EVIDENCE_REFS) {
            return List.copyOf(ordered);
        }
        ordered.sort(Comparator.comparingInt(DeterministicAuditor::evidenceBundleRank));
        return List.copyOf(ordered.subList(0, AuditedRecord.MAX_EVIDENCE_REFS));
    }

    private static int evidenceBundleRank(EvidenceRef ref) {
        String uri = ref.uri().toLowerCase(Locale.ROOT);
        if (uri.contains("qa-evidence/console/") || uri.contains("/console/")) {
            return 0;
        }
        if (uri.contains("qa-evidence/network/") || uri.contains("/network/")) {
            return 1;
        }
        if (uri.contains("traces") && uri.endsWith(".zip")) {
            return 2;
        }
        if (uri.contains("desktop")) {
            return 3;
        }
        if (uri.contains("mobile")) {
            return 4;
        }
        return 5;
    }

    private static void replace(List<AuditedRecord> records, String id, AuditedRecord next) {
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).id().equals(id)) {
                records.set(index, next);
                return;
            }
        }
        throw new IllegalArgumentException("unknown audited record: " + id);
    }

    private static AuditedRecord find(List<AuditedRecord> records, String id) {
        return records.stream()
                .filter(record -> record.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown audited record: " + id));
    }

    private static void requireHead(AuditedTaskState head) {
        if (head == null) {
            throw new IllegalArgumentException("audited state head must not be null");
        }
    }

    private static String normalize(String text) {
        String nfkc = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        return nfkc.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> values = new ArrayList<>(left);
        values.addAll(right);
        return values;
    }

    private static List<String> truncate(List<String> values, int max) {
        if (values.size() <= max) {
            return List.copyOf(values);
        }
        return List.copyOf(values.subList(0, max));
    }
}
