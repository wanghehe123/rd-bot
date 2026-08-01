package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialEventRow;
import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialRow;
import com.wish.rd.bootstrap.persistence.mapper.CodingBenchmarkTrialEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.CodingBenchmarkTrialMapper;
import com.wish.rd.engine.evaluation.CodingBenchmarkTrialStore;
import com.wish.rd.engine.evaluation.CodingBenchmarkTrialTransitionPolicy;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation with one-row lease claims, optimistic transitions and atomic events.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresCodingBenchmarkTrialStore implements CodingBenchmarkTrialStore {
    private final CodingBenchmarkTrialMapper trialMapper;
    private final CodingBenchmarkTrialEventMapper eventMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final CodingBenchmarkTrialTransitionPolicy transitionPolicy = new CodingBenchmarkTrialTransitionPolicy();

    public PostgresCodingBenchmarkTrialStore(
            CodingBenchmarkTrialMapper trialMapper,
            CodingBenchmarkTrialEventMapper eventMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.trialMapper = Objects.requireNonNull(trialMapper, "trial mapper must not be null");
        this.eventMapper = Objects.requireNonNull(eventMapper, "event mapper must not be null");
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    @Override
    @Transactional
    public void createAll(String campaignId, List<CodingBenchmarkTrial> trials) {
        long campaign = parseCampaignId(campaignId);
        List<CodingBenchmarkTrial> safeTrials = List.copyOf(Objects.requireNonNull(trials, "trials must not be null"));
        if (safeTrials.isEmpty()) {
            throw new IllegalArgumentException("coding benchmark plan must not be empty");
        }
        for (CodingBenchmarkTrial trial : safeTrials) {
            if (!campaignId.equals(trial.campaignId())) {
                throw new IllegalArgumentException("trial campaign does not match create request: " + trial.trialId());
            }
            trialMapper.insertTrial(toRow(trial));
            appendEvent(trial.campaignId(), trial.trialId(), null, trial.status(), trial.version(),
                    "trial registered", trial.errorCategory(), trial.errorMessage(), trial.createdAtEpochMillis());
        }
    }

    @Override
    public Optional<CodingBenchmarkTrial> find(String trialId) {
        return Optional.ofNullable(trialMapper.selectById(requireText(trialId, "trial id"))).map(this::toTrial);
    }

    @Override
    public List<CodingBenchmarkTrial> listByCampaign(String campaignId) {
        return trialMapper.selectByCampaign(parseCampaignId(campaignId)).stream().map(this::toTrial).toList();
    }

    @Override
    @Transactional
    public Optional<CodingBenchmarkTrial> claimNext(String campaignId, String leaseOwner, long now, long leaseMillis) {
        if (now < 0L || leaseMillis < 1L) {
            throw new IllegalArgumentException("lease time must be positive");
        }
        CodingBenchmarkTrialRow claimed = trialMapper.claimNext(
                parseCampaignId(campaignId), requireText(leaseOwner, "lease owner"),
                PostgresPersistenceSupport.toDateTime(now), PostgresPersistenceSupport.toDateTime(now + leaseMillis)
        );
        if (claimed == null) {
            return Optional.empty();
        }
        CodingBenchmarkTrial trial = toTrial(claimed);
        CodingBenchmarkTrialStatus previous = enumValue(claimed.previousStatus, CodingBenchmarkTrialStatus.QUEUED);
        appendEvent(trial.campaignId(), trial.trialId(), previous, trial.status(), trial.version(),
                "lease claimed", "", "", now);
        return Optional.of(trial);
    }

    @Override
    @Transactional
    public CodingBenchmarkTrial transition(
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
        CodingBenchmarkTrialRow updated = trialMapper.transition(
                requireText(trialId, "trial id"), safeExpected.name(), expectedVersion, safeTarget.name(),
                safeVerdict.name(),
                safe(errorCategory), bounded(errorMessage, 2_000), PostgresPersistenceSupport.toDateTime(now)
        );
        if (updated == null) {
            throw new IllegalStateException("coding benchmark trial compare-and-set failed: " + trialId);
        }
        CodingBenchmarkTrial trial = toTrial(updated);
        appendEvent(trial.campaignId(), trial.trialId(), safeExpected, safeTarget, trial.version(),
                "trial transitioned", trial.errorCategory(), trial.errorMessage(), now);
        return trial;
    }

    @Override
    public List<CodingBenchmarkTrialEvent> listEvents(String trialId) {
        String safeTrialId = requireText(trialId, "trial id");
        if (trialMapper.selectById(safeTrialId) == null) {
            throw new NoSuchElementException("coding benchmark trial not found: " + safeTrialId);
        }
        return eventMapper.selectList(new QueryWrapper<CodingBenchmarkTrialEventRow>()
                        .eq("trial_id", safeTrialId).orderByAsc("occurred_at", "id"))
                .stream().map(this::toEvent).toList();
    }

    private CodingBenchmarkTrialRow toRow(CodingBenchmarkTrial trial) {
        CodingBenchmarkTrialRow row = new CodingBenchmarkTrialRow();
        row.id = trial.trialId();
        row.campaignId = parseCampaignId(trial.campaignId());
        row.caseId = trial.caseId();
        row.arm = trial.arm().name();
        row.replicateNo = trial.replicateNo();
        row.status = trial.status().name();
        row.verdict = trial.verdict().name();
        row.attemptNo = trial.attemptNo();
        row.version = trial.version();
        row.leaseOwner = trial.leaseOwner();
        row.leaseExpiresAt = trial.leaseExpiresAtEpochMillis() == 0L
                ? null : PostgresPersistenceSupport.toDateTime(trial.leaseExpiresAtEpochMillis());
        row.errorCategory = trial.errorCategory();
        row.errorMessage = bounded(trial.errorMessage(), 2_000);
        row.createdAt = PostgresPersistenceSupport.toDateTime(trial.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(trial.updatedAtEpochMillis());
        return row;
    }

    private CodingBenchmarkTrial toTrial(CodingBenchmarkTrialRow row) {
        return new CodingBenchmarkTrial(
                requireText(row.id, "persisted trial id"), PostgresPersistenceSupport.idString(row.campaignId),
                requireText(row.caseId, "persisted case id"), enumValue(row.arm, CodingBenchmarkArm.A),
                value(row.replicateNo, 0), enumValue(row.status, CodingBenchmarkTrialStatus.QUEUED),
                enumValue(row.verdict, CodingBenchmarkVerdict.PENDING), value(row.attemptNo, 1), value(row.version, 0L),
                safe(row.leaseOwner), row.leaseExpiresAt == null ? 0L : PostgresPersistenceSupport.toEpochMillis(row.leaseExpiresAt),
                safe(row.errorCategory), safe(row.errorMessage),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt), PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private CodingBenchmarkTrialEvent toEvent(CodingBenchmarkTrialEventRow row) {
        return new CodingBenchmarkTrialEvent(
                PostgresPersistenceSupport.idString(row.id), PostgresPersistenceSupport.idString(row.campaignId),
                requireText(row.trialId, "persisted event trial id"),
                row.fromStatus == null || row.fromStatus.isBlank() ? null : enumValue(row.fromStatus, CodingBenchmarkTrialStatus.QUEUED),
                enumValue(row.toStatus, CodingBenchmarkTrialStatus.QUEUED), value(row.version, 0L),
                safe(row.message), safe(row.errorCategory), safe(row.errorMessage),
                PostgresPersistenceSupport.toEpochMillis(row.occurredAt)
        );
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
        CodingBenchmarkTrialEventRow row = new CodingBenchmarkTrialEventRow();
        row.id = PostgresPersistenceSupport.parseId(idGenerator.nextIdString());
        row.campaignId = parseCampaignId(campaignId);
        row.trialId = trialId;
        row.fromStatus = from == null ? "" : from.name();
        row.toStatus = to.name();
        row.version = version;
        row.message = safe(message);
        row.errorCategory = safe(errorCategory);
        row.errorMessage = bounded(errorMessage, 2_000);
        row.occurredAt = PostgresPersistenceSupport.toDateTime(occurredAt);
        eventMapper.insertEvent(row);
    }

    private static long parseCampaignId(String campaignId) {
        return PostgresPersistenceSupport.parseId(requireText(campaignId, "campaign id"));
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

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static <T extends Enum<T>> T enumValue(String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<T> type = (Class<T>) fallback.getDeclaringClass();
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
