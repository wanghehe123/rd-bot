package com.wish.rd.engine.requirement.audit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded Host audited-gap prompt section shared by Orchestrator and Engine.
 *
 * <p>Never includes {@code AgentStageRun.errorMessage} or compiler/stderr dumps.
 * Callers that lack a head must pass {@code null} and receive an empty string.
 */
public final class AuditedGapSection {

    public static final int MAX_IDS = 16;
    public static final int MAX_CHARS = 2_000;

    private AuditedGapSection() {
    }

    /**
     * Renders the Host gap section for a retry / HOST_VERIFY_FIX / QA-protocol-retry prompt.
     *
     * @param head current audited head, or {@code null}
     * @param lastRun most recent audit run, or {@code null}
     * @return prompt section or empty string
     */
    public static String render(AuditedTaskState head, AuditRun lastRun) {
        if (head == null) {
            return "";
        }
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> untrusted = new LinkedHashSet<>();
        if (lastRun != null) {
            blockers.addAll(nullToEmpty(lastRun.blockers()));
            missing.addAll(nullToEmpty(lastRun.missing()));
            untrusted.addAll(nullToEmpty(lastRun.untrusted()));
        }
        for (AuditedRecord record : head.records()) {
            if (record.status() == AuditedRecordStatus.BLOCKED) {
                blockers.add(record.id());
            } else if (record.status() == AuditedRecordStatus.PENDING) {
                missing.add(record.id());
            } else if (record.status() == AuditedRecordStatus.UNTRUSTED) {
                untrusted.add(record.id());
            }
        }
        List<String> ordered = new ArrayList<>();
        appendCapped(ordered, blockers);
        appendCapped(ordered, missing);
        appendCapped(ordered, untrusted);
        if (ordered.isEmpty()) {
            return "";
        }
        List<String> evidence = evidenceUris(head, lastRun);
        String body = format(head, ordered, blockers, missing, untrusted, evidence);
        while (body.length() > MAX_CHARS && !evidence.isEmpty()) {
            evidence = new ArrayList<>(evidence.subList(0, evidence.size() - 1));
            body = format(head, ordered, blockers, missing, untrusted, evidence);
        }
        while (body.length() > MAX_CHARS && ordered.size() > 1) {
            ordered = new ArrayList<>(ordered.subList(0, ordered.size() - 1));
            body = format(head, ordered, blockers, missing, untrusted, evidence);
        }
        if (body.length() > MAX_CHARS) {
            return body.substring(0, MAX_CHARS);
        }
        return body;
    }

    private static String format(
            AuditedTaskState head,
            List<String> ordered,
            Set<String> blockers,
            Set<String> missing,
            Set<String> untrusted,
            List<String> evidence
    ) {
        Set<String> kept = new LinkedHashSet<>(ordered);
        return """
                # 已审计缺口（Host）
                state_version: %d hash: %s
                missing: %s
                blockers: %s
                untrusted: %s
                evidence: %s
                禁止把 UNTRUSTED 声明当已验证事实。原始失败文本只在 RESULT_JSON / AGENT_EVENTS。
                """.formatted(
                head.stateVersion(),
                head.stateHash() == null ? "" : head.stateHash(),
                joinIds(missing, kept),
                joinIds(blockers, kept),
                joinIds(untrusted, kept),
                evidence.isEmpty() ? "(empty)" : String.join(", ", evidence)
        ).strip();
    }

    private static List<String> evidenceUris(AuditedTaskState head, AuditRun lastRun) {
        if (lastRun != null && lastRun.sourceRefs() != null && !lastRun.sourceRefs().isEmpty()) {
            return new ArrayList<>(lastRun.sourceRefs());
        }
        List<String> uris = new ArrayList<>();
        for (AuditedRecord record : head.records()) {
            if (record.status() != AuditedRecordStatus.COMPLETED) {
                continue;
            }
            for (EvidenceRef ref : record.evidenceRefs()) {
                if (ref != null && ref.uri() != null && !ref.uri().isBlank()) {
                    uris.add(ref.uri());
                }
            }
        }
        return uris;
    }

    private static void appendCapped(List<String> ordered, Set<String> ids) {
        for (String id : ids) {
            if (id == null || id.isBlank() || ordered.contains(id)) {
                continue;
            }
            if (ordered.size() >= MAX_IDS) {
                return;
            }
            ordered.add(id);
        }
    }

    private static String joinIds(Set<String> source, Set<String> kept) {
        List<String> ids = new ArrayList<>();
        for (String id : source) {
            if (kept.contains(id)) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? "(empty)" : String.join(", ", ids);
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
