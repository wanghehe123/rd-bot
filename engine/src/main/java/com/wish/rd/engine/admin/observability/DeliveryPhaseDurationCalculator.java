package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StatusEventObservation;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes phase durations from adjacent {@code entered_at} values.
 *
 * <p>Host-finalizer {@code duration_ms = 0} is ignored. Negative intervals are
 * counted as invalid instead of becoming zero samples.
 */
public final class DeliveryPhaseDurationCalculator {

    static final String PHASE_END_TO_END = "end_to_end";
    static final String PHASE_MATERIAL = "material";
    static final String PHASE_CONTEXT = "context";
    static final String PHASE_PLAN = "plan";
    static final String PHASE_POLICY = "policy";
    static final String PHASE_EXECUTION = "execution";
    static final String PHASE_VALIDATION = "validation";
    static final String PHASE_PUBLICATION = "publication";
    static final String PHASE_REPORTING = "reporting";

    /**
     * Result of one task's phase split.
     *
     * @param phaseDurationsMillis phase → duration
     * @param invalidCount negative or missing timestamps
     */
    public record PhaseSplit(Map<String, Long> phaseDurationsMillis, int invalidCount) {
        public PhaseSplit {
            phaseDurationsMillis = phaseDurationsMillis == null ? Map.of() : Map.copyOf(phaseDurationsMillis);
        }
    }

    /**
     * Splits a status timeline into phase durations.
     *
     * @param events status events
     * @return split
     */
    public PhaseSplit split(List<StatusEventObservation> events) {
        if (events == null || events.isEmpty()) {
            return new PhaseSplit(Map.of(), 0);
        }
        List<StatusEventObservation> ordered = events.stream()
                .filter(event -> event != null && event.enteredAt() != null)
                .sorted(Comparator.comparing(StatusEventObservation::enteredAt)
                        .thenComparing(event -> safe(event.status())))
                .toList();
        int invalid = events.size() - ordered.size();
        Map<String, Long> durations = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size() - 1; index++) {
            StatusEventObservation from = ordered.get(index);
            StatusEventObservation to = ordered.get(index + 1);
            long millis = Duration.between(from.enteredAt(), to.enteredAt()).toMillis();
            if (millis < 0L) {
                invalid++;
                continue;
            }
            String phase = phaseOf(from.status());
            if (phase.isBlank()) {
                continue;
            }
            durations.merge(phase, millis, Long::sum);
        }
        return new PhaseSplit(durations, invalid);
    }

    /**
     * Role attempt duration from started/finished timestamps.
     *
     * @param startedAt start
     * @param finishedAt finish
     * @return duration millis, or empty when running/missing/negative
     */
    public DurationSample roleDuration(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return DurationSample.invalid(startedAt == null && finishedAt == null);
        }
        long millis = Duration.between(startedAt, finishedAt).toMillis();
        if (millis < 0L) {
            return DurationSample.invalid(true);
        }
        return DurationSample.valid(millis);
    }

    /**
     * @param status task status
     * @return phase name or empty
     */
    public String phaseOf(String status) {
        return switch (safe(status).toUpperCase(Locale.ROOT)) {
            case "CREATED", "MATERIAL_COLLECTING" -> PHASE_MATERIAL;
            case "MATERIAL_READY", "CONTEXT_BUILDING" -> PHASE_CONTEXT;
            case "CONTEXT_READY", "PLAN_GENERATING" -> PHASE_PLAN;
            case "PLAN_GENERATED", "WAITING_POLICY", "WAITING_APPROVAL" -> PHASE_POLICY;
            case "EXECUTING" -> PHASE_EXECUTION;
            case "VALIDATING" -> PHASE_VALIDATION;
            case "PR_CREATING", "COMMITTED", "MERGED" -> PHASE_PUBLICATION;
            case "REPORTING" -> PHASE_REPORTING;
            default -> "";
        };
    }

    /**
     * Optional duration sample.
     *
     * @param millis duration
     * @param valid true when usable
     * @param invalid true when a bad observation was seen
     */
    public record DurationSample(long millis, boolean valid, boolean invalid) {
        static DurationSample valid(long millis) {
            return new DurationSample(millis, true, false);
        }

        static DurationSample invalid(boolean invalid) {
            return new DurationSample(0L, false, invalid);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
