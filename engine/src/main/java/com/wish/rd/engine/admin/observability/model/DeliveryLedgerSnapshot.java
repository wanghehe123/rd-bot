package com.wish.rd.engine.admin.observability.model;

import java.time.Instant;
import java.util.List;

/**
 * Raw ledger rows loaded by {@code DeliveryObservabilitySnapshotPort}.
 *
 * @param available query succeeded
 * @param generatedAt snapshot time
 * @param warning empty when healthy
 * @param tasks task observations already constrained by project/window at the adapter
 * @param commands durable command rows for backlog
 */
public record DeliveryLedgerSnapshot(
        boolean available,
        Instant generatedAt,
        String warning,
        List<TaskObservation> tasks,
        List<CommandObservation> commands
) {

    public DeliveryLedgerSnapshot {
        generatedAt = generatedAt == null ? Instant.EPOCH : generatedAt;
        warning = warning == null ? "" : warning;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    /**
     * @param generatedAt time
     * @param warning warning
     * @return failed snapshot
     */
    public static DeliveryLedgerSnapshot failed(Instant generatedAt, String warning) {
        return new DeliveryLedgerSnapshot(false, generatedAt, warning, List.of(), List.of());
    }

    /**
     * One requirement task and its evidence.
     *
     * @param taskId task id
     * @param projectId project id
     * @param title title
     * @param status status
     * @param acceptedAt accepted time
     * @param terminalAt terminal time or null
     * @param pullRequestUrl PR url
     * @param errorCategory structured category
     * @param statusEvents timeline
     * @param stages role attempts
     */
    public record TaskObservation(
            String taskId,
            String projectId,
            String title,
            String status,
            Instant acceptedAt,
            Instant terminalAt,
            String pullRequestUrl,
            String errorCategory,
            List<StatusEventObservation> statusEvents,
            List<StageObservation> stages
    ) {
        public TaskObservation {
            pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl;
            errorCategory = errorCategory == null ? "" : errorCategory;
            statusEvents = statusEvents == null ? List.of() : List.copyOf(statusEvents);
            stages = stages == null ? List.of() : List.copyOf(stages);
        }

        /**
         * @return true when terminalAt is present
         */
        public boolean terminal() {
            return terminalAt != null;
        }
    }

    /**
     * Task status event. {@code durationMs} from host-finalizer is often 0 and must not be trusted.
     *
     * @param status status
     * @param enteredAt enter time
     * @param durationMs stored duration
     */
    public record StatusEventObservation(String status, Instant enteredAt, long durationMs) {
    }

    /**
     * Role stage attempt.
     *
     * @param stageRunId id
     * @param role role
     * @param status status
     * @param attemptNo attempt
     * @param startedAt start
     * @param finishedAt finish or null
     * @param errorCategory category
     * @param attempts provider attempts already deduped by attemptId at the adapter
     */
    public record StageObservation(
            String stageRunId,
            String role,
            String status,
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            String errorCategory,
            List<AttemptUsage> attempts
    ) {
        public StageObservation {
            errorCategory = errorCategory == null ? "" : errorCategory;
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        /**
         * Compatibility constructor when usage was not loaded.
         *
         * @param stageRunId id
         * @param role role
         * @param status status
         * @param attemptNo attempt
         * @param startedAt start
         * @param finishedAt finish
         * @param errorCategory category
         */
        public StageObservation(
                String stageRunId,
                String role,
                String status,
                int attemptNo,
                Instant startedAt,
                Instant finishedAt,
                String errorCategory
        ) {
            this(stageRunId, role, status, attemptNo, startedAt, finishedAt, errorCategory, List.of());
        }

        /**
         * @return true when finished
         */
        public boolean finished() {
            return finishedAt != null;
        }
    }

    /**
     * Deduped Provider attempt usage. {@code attemptId} is the immutable key.
     *
     * @param attemptId immutable attempt id
     * @param runtime allowlisted runtime
     * @param provider allowlisted provider or OTHER
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     * @param cacheTokens cache-read tokens
     * @param estimatedCostCny estimated CNY or {@code NaN}
     * @param usageAvailable whether token counts were observed
     * @param firstTokenMillis first-token duration; negative when missing
     * @param firstProviderResponseMillis first response duration; negative when missing
     */
    public record AttemptUsage(
            String attemptId,
            String runtime,
            String provider,
            long inputTokens,
            long outputTokens,
            long cacheTokens,
            double estimatedCostCny,
            boolean usageAvailable,
            long firstTokenMillis,
            long firstProviderResponseMillis
    ) {
        public AttemptUsage {
            attemptId = attemptId == null ? "" : attemptId.strip();
            runtime = runtime == null || runtime.isBlank() ? "unknown" : runtime.strip();
            provider = provider == null || provider.isBlank() ? "OTHER" : provider.strip();
            inputTokens = Math.max(0L, inputTokens);
            outputTokens = Math.max(0L, outputTokens);
            cacheTokens = Math.max(0L, cacheTokens);
        }
    }

    /**
     * Durable stage command for backlog/oldest-age gauges.
     *
     * @param commandId id
     * @param status status
     * @param resource resource class
     * @param createdAt created
     * @param waiting true when pending/retryable/expired lease
     */
    public record CommandObservation(
            String commandId,
            String status,
            String resource,
            Instant createdAt,
            boolean waiting
    ) {
        public CommandObservation {
            resource = resource == null || resource.isBlank() ? "GENERIC" : resource;
        }
    }
}
