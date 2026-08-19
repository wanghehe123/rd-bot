package com.wish.rd.engine.evaluation.impl;

import com.wish.rd.engine.evaluation.CodingBenchmarkTrialStore;
import com.wish.rd.engine.evaluation.CodingBenchmarkTrialTransitionPolicy;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Memory-mode trial store used when PostgreSQL is not the knowledge store. */
public final class InMemoryCodingBenchmarkTrialStore implements CodingBenchmarkTrialStore {
    private final Map<String, CodingBenchmarkTrial> trials = new ConcurrentHashMap<>();
    private final Map<String, List<CodingBenchmarkTrialEvent>> events = new ConcurrentHashMap<>();
    private final CodingBenchmarkTrialTransitionPolicy transitionPolicy = new CodingBenchmarkTrialTransitionPolicy();
    private final AtomicLong eventIds = new AtomicLong();

    @Override
    public synchronized void createAll(String campaignId, List<CodingBenchmarkTrial> values) {
        String campaign = requireText(campaignId, "campaign id");
        List<CodingBenchmarkTrial> safeTrials = List.copyOf(Objects.requireNonNull(values, "trials must not be null"));
        if (safeTrials.isEmpty()) {
            throw new IllegalArgumentException("coding benchmark plan must not be empty");
        }
        for (CodingBenchmarkTrial trial : safeTrials) {
            if (!campaign.equals(trial.campaignId())) {
                throw new IllegalArgumentException("trial campaign does not match create request: " + trial.trialId());
            }
            if (trials.putIfAbsent(trial.trialId(), trial) != null) {
                throw new IllegalStateException("coding benchmark trial already exists: " + trial.trialId());
            }
            appendEvent(trial.campaignId(), trial.trialId(), null, trial.status(), trial.version(),
                    "trial registered", trial.errorCategory(), trial.errorMessage(), trial.createdAtEpochMillis());
        }
    }

    @Override
    public Optional<CodingBenchmarkTrial> find(String trialId) {
        return Optional.ofNullable(trials.get(requireText(trialId, "trial id")));
    }

    @Override
    public List<CodingBenchmarkTrial> listByCampaign(String campaignId) {
        String campaign = requireText(campaignId, "campaign id");
        return trials.values().stream()
                .filter(trial -> campaign.equals(trial.campaignId()))
                .sorted(Comparator.comparingLong(CodingBenchmarkTrial::createdAtEpochMillis)
                        .thenComparing(CodingBenchmarkTrial::trialId))
                .toList();
    }

    @Override
    public synchronized Optional<CodingBenchmarkTrial> claimNext(
            String campaignId, String leaseOwner, long now, long leaseMillis
    ) {
        if (now < 0L || leaseMillis < 1L) {
            throw new IllegalArgumentException("lease time must be positive");
        }
        String owner = requireText(leaseOwner, "lease owner");
        Set<String> activeCases = listByCampaign(campaignId).stream()
                .filter(trial -> trial.status() == CodingBenchmarkTrialStatus.RUNNING_AGENTS
                        || trial.status() == CodingBenchmarkTrialStatus.RUNNING_ORACLE
                        || (trial.status() == CodingBenchmarkTrialStatus.PREPARING
                        && trial.leaseExpiresAtEpochMillis() > now))
                .map(CodingBenchmarkTrial::caseId)
                .collect(Collectors.toUnmodifiableSet());
        for (CodingBenchmarkTrial trial : listByCampaign(campaignId)) {
            boolean queued = trial.status() == CodingBenchmarkTrialStatus.QUEUED;
            boolean expiredPreparing = trial.status() == CodingBenchmarkTrialStatus.PREPARING
                    && trial.leaseExpiresAtEpochMillis() <= now;
            if ((queued || expiredPreparing) && !activeCases.contains(trial.caseId())) {
                CodingBenchmarkTrial claimed = new CodingBenchmarkTrial(
                        trial.trialId(), trial.campaignId(), trial.caseId(), trial.arm(), trial.replicateNo(),
                        CodingBenchmarkTrialStatus.PREPARING, trial.verdict(), trial.attemptNo(),
                        trial.version() + 1L, owner, now + leaseMillis, "", "",
                        trial.createdAtEpochMillis(), now);
                trials.put(trial.trialId(), claimed);
                appendEvent(trial.campaignId(), trial.trialId(), trial.status(), claimed.status(),
                        claimed.version(), "lease claimed", "", "", now);
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized CodingBenchmarkTrial transition(
            String trialId,
            CodingBenchmarkTrialStatus expected,
            long expectedVersion,
            CodingBenchmarkTrialStatus target,
            CodingBenchmarkVerdict verdict,
            String errorCategory,
            String errorMessage,
            long now
    ) {
        if (expectedVersion < 0L || now < 0L) {
            throw new IllegalArgumentException("trial version and timestamp must not be negative");
        }
        CodingBenchmarkTrialStatus safeExpected = Objects.requireNonNull(expected, "expected status must not be null");
        CodingBenchmarkTrialStatus safeTarget = Objects.requireNonNull(target, "target status must not be null");
        CodingBenchmarkVerdict safeVerdict = verdict == null ? CodingBenchmarkVerdict.PENDING : verdict;
        transitionPolicy.requireTransition(safeExpected, safeTarget);
        if (safeTarget == CodingBenchmarkTrialStatus.RETRY_PENDING
                && safeVerdict != CodingBenchmarkVerdict.INFRA_ERROR) {
            throw new IllegalStateException("only an infrastructure error may enter the coding benchmark retry path");
        }
        CodingBenchmarkTrial current = trials.get(requireText(trialId, "trial id"));
        if (current == null) {
            throw new NoSuchElementException("coding benchmark trial not found: " + trialId);
        }
        if (current.status() != safeExpected || current.version() != expectedVersion) {
            throw new IllegalStateException("coding benchmark trial compare-and-set failed: " + trialId);
        }
        boolean clearLease = safeTarget == CodingBenchmarkTrialStatus.QUEUED
                || safeTarget == CodingBenchmarkTrialStatus.RETRY_PENDING
                || safeTarget.isTerminal();
        CodingBenchmarkTrial updated = new CodingBenchmarkTrial(
                current.trialId(), current.campaignId(), current.caseId(), current.arm(), current.replicateNo(),
                safeTarget, safeVerdict, current.attemptNo(), expectedVersion + 1L,
                clearLease ? "" : current.leaseOwner(),
                clearLease ? 0L : current.leaseExpiresAtEpochMillis(),
                safe(errorCategory), bounded(errorMessage, 2_000),
                current.createdAtEpochMillis(), now);
        trials.put(trialId, updated);
        appendEvent(updated.campaignId(), updated.trialId(), safeExpected, safeTarget, updated.version(),
                "trial transitioned", updated.errorCategory(), updated.errorMessage(), now);
        return updated;
    }

    @Override
    public List<CodingBenchmarkTrialEvent> listEvents(String trialId) {
        String id = requireText(trialId, "trial id");
        if (!trials.containsKey(id)) {
            throw new NoSuchElementException("coding benchmark trial not found: " + id);
        }
        return List.copyOf(events.getOrDefault(id, List.of()));
    }

    private void appendEvent(
            String campaignId,
            String trialId,
            CodingBenchmarkTrialStatus from,
            CodingBenchmarkTrialStatus to,
            long version,
            String message,
            String errorCategory,
            String errorMessage,
            long occurredAt
    ) {
        CodingBenchmarkTrialEvent event = new CodingBenchmarkTrialEvent(
                String.valueOf(eventIds.incrementAndGet()), campaignId, trialId, from, to, version,
                safe(message), safe(errorCategory), bounded(errorMessage, 2_000), occurredAt);
        events.computeIfAbsent(trialId, ignored -> new ArrayList<>()).add(event);
    }

    private static String requireText(String value, String field) {
        String normalized = safe(value).strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String bounded(String value, int maxChars) {
        String normalized = safe(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }
}
