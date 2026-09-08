package com.wish.rd.engine.admin.observability;

import java.time.Instant;
import java.util.List;

/**
 * Deterministic delivery-observability catalog shared by query, mapper and
 * Prometheus consistency tests.
 *
 * <p>Clock: window {@code [2026-08-14T00:00:00Z, 2026-08-15T00:00:00Z)} (24h).
 * Host-finalizer rows keep {@code durationMs = 0} on purpose so calculators must
 * use adjacent {@code enteredAt} instead of trusting stored duration.
 */
public final class DeliveryObservabilityFixtures {

    public static final Instant WINDOW_END = Instant.parse("2026-08-15T00:00:00Z");
    public static final Instant WINDOW_START = Instant.parse("2026-08-14T00:00:00Z");
    public static final String PROJECT_A = "project-a";
    public static final String PROJECT_B = "project-b";

    private DeliveryObservabilityFixtures() {
    }

    /**
     * Returns the full catalog covering success, running, retry, human failure,
     * empty-window, negative duration and missing usage.
     *
     * @return immutable catalog
     */
    public static Catalog catalog() {
        return new Catalog(
                List.of(successTask(), runningTask(), retryTask(), humanFailureTask(), negativeDurationTask(),
                        missingUsageTask()),
                List.of()
        );
    }

    /**
     * Returns a catalog with no terminal samples in the 24h window.
     *
     * @return empty-window catalog
     */
    public static Catalog emptyWindow() {
        return new Catalog(List.of(), List.of());
    }

    /**
     * Successful terminal delivery with valid phase timestamps, QA pass, PR,
     * tokens and estimated cost.
     *
     * @return success fixture
     */
    public static TaskFixture successTask() {
        Instant accepted = Instant.parse("2026-08-14T01:00:00Z");
        Instant materialReady = Instant.parse("2026-08-14T01:05:00Z");
        Instant contextReady = Instant.parse("2026-08-14T01:08:00Z");
        Instant planReady = Instant.parse("2026-08-14T01:18:00Z");
        Instant executing = Instant.parse("2026-08-14T01:20:00Z");
        Instant validating = Instant.parse("2026-08-14T01:50:00Z");
        Instant publishing = Instant.parse("2026-08-14T02:00:00Z");
        Instant completed = Instant.parse("2026-08-14T02:10:00Z");
        return new TaskFixture(
                "1001",
                PROJECT_A,
                "success-delivery",
                "COMPLETED",
                accepted,
                completed,
                "https://github.example/pr/1",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("MATERIAL_READY", materialReady, 0L),
                        event("CONTEXT_READY", contextReady, 0L),
                        event("PLAN_GENERATED", planReady, 0L),
                        event("EXECUTING", executing, 0L),
                        event("VALIDATING", validating, 0L),
                        event("PR_CREATING", publishing, 0L),
                        event("COMPLETED", completed, 0L)
                ),
                List.of(
                        stage("review-1", "REQUIREMENT_REVIEWER", "SUCCEEDED", 1,
                                executing, executing.plusSeconds(120),
                                attempt("pi", "anthropic", "claude-alias", 100, 40, 10, "0.12", true)),
                        stage("coding-1", "CODING_AGENT", "SUCCEEDED", 1,
                                executing.plusSeconds(120), validating,
                                attempt("pi", "anthropic", "claude-alias", 800, 200, 50, "1.80", true)),
                        stage("qa-1", "QA_AGENT", "SUCCEEDED", 1,
                                validating, publishing,
                                attempt("pi", "anthropic", "claude-alias", 50, 20, 0, "0.05", true))
                ),
                List.of(retrieval("ret-1", "SUCCEEDED", 1_500L, 3, false))
        );
    }

    /**
     * In-progress task that must not enter completed percentiles.
     *
     * @return running fixture
     */
    public static TaskFixture runningTask() {
        Instant accepted = Instant.parse("2026-08-14T22:00:00Z");
        Instant executing = Instant.parse("2026-08-14T22:10:00Z");
        return new TaskFixture(
                "1002",
                PROJECT_A,
                "running-delivery",
                "EXECUTING",
                accepted,
                null,
                "",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("EXECUTING", executing, 0L)
                ),
                List.of(stage("coding-running", "CODING_AGENT", "RUNNING", 1, executing, null,
                        attempt("pi", "anthropic", "claude-alias", 0, 0, 0, "", false))),
                List.of()
        );
    }

    /**
     * Coding role retried; both finished attempts are counted, usage deduped by attempt id.
     *
     * @return retry fixture
     */
    public static TaskFixture retryTask() {
        Instant accepted = Instant.parse("2026-08-14T03:00:00Z");
        Instant completed = Instant.parse("2026-08-14T04:00:00Z");
        Instant firstStart = Instant.parse("2026-08-14T03:10:00Z");
        Instant firstEnd = Instant.parse("2026-08-14T03:20:00Z");
        Instant secondStart = Instant.parse("2026-08-14T03:21:00Z");
        Instant secondEnd = Instant.parse("2026-08-14T03:40:00Z");
        return new TaskFixture(
                "1003",
                PROJECT_B,
                "retry-delivery",
                "COMPLETED",
                accepted,
                completed,
                "https://github.example/pr/3",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("COMPLETED", completed, 0L)
                ),
                List.of(
                        stage("coding-retry-1", "CODING_AGENT", "FAILED_RETRYABLE", 1, firstStart, firstEnd,
                                attempt("pi", "deepseek", "deepseek-alias", 100, 10, 0, "0.20", true)),
                        stage("coding-retry-2", "CODING_AGENT", "SUCCEEDED", 2, secondStart, secondEnd,
                                attempt("pi", "anthropic", "claude-alias", 400, 80, 20, "0.90", true)),
                        stage("qa-retry", "QA_AGENT", "SUCCEEDED", 1,
                                secondEnd, completed,
                                attempt("pi", "anthropic", "claude-alias", 40, 10, 0, "0.04", true))
                ),
                List.of()
        );
    }

    /**
     * Human failure with blank error category must fold into UNKNOWN.
     *
     * @return human-failure fixture
     */
    public static TaskFixture humanFailureTask() {
        Instant accepted = Instant.parse("2026-08-14T05:00:00Z");
        Instant failed = Instant.parse("2026-08-14T05:30:00Z");
        return new TaskFixture(
                "1004",
                PROJECT_A,
                "human-failure",
                "FAILED_NEEDS_HUMAN",
                accepted,
                failed,
                "",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("FAILED_NEEDS_HUMAN", failed, 0L)
                ),
                List.of(stage("qa-fail", "QA_AGENT", "FAILED_NEEDS_HUMAN", 1,
                        Instant.parse("2026-08-14T05:20:00Z"), failed,
                        new ProviderAttemptFixture("attempt-blank", "pi", "anthropic", "claude-alias",
                                10, 5, 0, "", true, ""))),
                List.of()
        );
    }

    /**
     * Finished stage with {@code finishedAt < startedAt} is an invalid observation.
     *
     * @return negative-duration fixture
     */
    public static TaskFixture negativeDurationTask() {
        Instant accepted = Instant.parse("2026-08-14T06:00:00Z");
        Instant completed = Instant.parse("2026-08-14T06:10:00Z");
        Instant started = Instant.parse("2026-08-14T06:08:00Z");
        Instant finished = Instant.parse("2026-08-14T06:07:00Z");
        return new TaskFixture(
                "1005",
                PROJECT_A,
                "negative-duration",
                "COMPLETED",
                accepted,
                completed,
                "",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("COMPLETED", completed, 0L)
                ),
                List.of(stage("coding-negative", "CODING_AGENT", "SUCCEEDED", 1, started, finished,
                        attempt("pi", "anthropic", "claude-alias", 1, 1, 0, "0.01", true))),
                List.of()
        );
    }

    /**
     * COMMITTED with a PR URL and no COMPLETED status event. Must stay in-progress.
     *
     * @return committed-without-completion fixture
     */
    public static TaskFixture committedNoCompleteTask() {
        Instant accepted = Instant.parse("2026-08-14T08:00:00Z");
        Instant committed = Instant.parse("2026-08-14T08:30:00Z");
        return new TaskFixture(
                "2001",
                PROJECT_A,
                "committed-no-complete",
                "COMMITTED",
                accepted,
                committed,
                "https://github.example/pr/committed",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("COMMITTED", committed, 0L)
                ),
                List.of(),
                List.of()
        );
    }

    /**
     * MERGED after a COMPLETED status event. Counts as delivery success.
     *
     * @return merged-with-completed fixture
     */
    public static TaskFixture mergedWithCompleteTask() {
        Instant accepted = Instant.parse("2026-08-14T09:00:00Z");
        Instant completed = Instant.parse("2026-08-14T09:40:00Z");
        Instant merged = Instant.parse("2026-08-14T09:50:00Z");
        return new TaskFixture(
                "2002",
                PROJECT_A,
                "merged-with-complete",
                "MERGED",
                accepted,
                merged,
                "https://github.example/pr/merged-complete",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("COMPLETED", completed, 0L),
                        event("MERGED", merged, 0L)
                ),
                List.of(),
                List.of()
        );
    }

    /**
     * MERGED after COMMITTED only. Publication-terminal, not success or failure.
     *
     * @return merged-committed-only fixture
     */
    public static TaskFixture mergedCommittedOnlyTask() {
        Instant accepted = Instant.parse("2026-08-14T10:00:00Z");
        Instant committed = Instant.parse("2026-08-14T10:20:00Z");
        Instant merged = Instant.parse("2026-08-14T10:30:00Z");
        return new TaskFixture(
                "2003",
                PROJECT_A,
                "merged-committed-only",
                "MERGED",
                accepted,
                merged,
                "https://github.example/pr/merged-committed",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("COMMITTED", committed, 0L),
                        event("MERGED", merged, 0L)
                ),
                List.of(),
                List.of()
        );
    }

    /**
     * WAITING_USER_INPUT with a non-null terminalAt. Must stay in-progress.
     *
     * @return waiting-user-input fixture
     */
    public static TaskFixture waitingUserInputTask() {
        Instant accepted = Instant.parse("2026-08-14T11:00:00Z");
        Instant waiting = Instant.parse("2026-08-14T11:15:00Z");
        return new TaskFixture(
                "2004",
                PROJECT_A,
                "waiting-user-input",
                "WAITING_USER_INPUT",
                accepted,
                waiting,
                "",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("WAITING_USER_INPUT", waiting, 0L)
                ),
                List.of(),
                List.of()
        );
    }

    /**
     * WAITING_APPROVAL with a non-null terminalAt. Must stay in-progress.
     *
     * @return waiting-approval fixture
     */
    public static TaskFixture waitingApprovalTask() {
        Instant accepted = Instant.parse("2026-08-14T12:00:00Z");
        Instant waiting = Instant.parse("2026-08-14T12:10:00Z");
        return new TaskFixture(
                "2005",
                PROJECT_A,
                "waiting-approval",
                "WAITING_APPROVAL",
                accepted,
                waiting,
                "",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("WAITING_APPROVAL", waiting, 0L)
                ),
                List.of(),
                List.of()
        );
    }

    /**
     * Terminal success whose provider metadata has no tokens or cost.
     *
     * @return missing-usage fixture
     */
    public static TaskFixture missingUsageTask() {
        Instant accepted = Instant.parse("2026-08-14T07:00:00Z");
        Instant completed = Instant.parse("2026-08-14T07:20:00Z");
        return new TaskFixture(
                "1006",
                PROJECT_B,
                "missing-usage",
                "COMPLETED",
                accepted,
                completed,
                "",
                "",
                List.of(
                        event("CREATED", accepted, 0L),
                        event("COMPLETED", completed, 0L)
                ),
                List.of(stage("coding-no-usage", "CODING_AGENT", "SUCCEEDED", 1,
                        Instant.parse("2026-08-14T07:05:00Z"), Instant.parse("2026-08-14T07:15:00Z"),
                        attempt("claude-compat", "anthropic", "claude-alias", 0, 0, 0, "", false))),
                List.of()
        );
    }

    private static StatusEventFixture event(String status, Instant enteredAt, long durationMs) {
        return new StatusEventFixture(status, enteredAt, durationMs);
    }

    private static StageRunFixture stage(
            String stageRunId,
            String role,
            String status,
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            ProviderAttemptFixture attempt
    ) {
        return new StageRunFixture(stageRunId, role, status, attemptNo, startedAt, finishedAt, List.of(attempt));
    }

    private static ProviderAttemptFixture attempt(
            String runtime,
            String provider,
            String modelAlias,
            long inputTokens,
            long outputTokens,
            long cacheTokens,
            String estimatedCostCny,
            boolean usageAvailable
    ) {
        return new ProviderAttemptFixture(
                runtime + "-" + provider + "-" + inputTokens + "-" + outputTokens,
                runtime,
                provider,
                modelAlias,
                inputTokens,
                outputTokens,
                cacheTokens,
                estimatedCostCny,
                usageAvailable,
                "PROVIDER"
        );
    }

    private static RetrievalFixture retrieval(
            String runId,
            String status,
            long durationMs,
            int iterations,
            boolean degraded
    ) {
        return new RetrievalFixture(runId, status, durationMs, iterations, degraded,
                List.of(new RetrievalStepFixture(runId + "-step-1", durationMs / Math.max(iterations, 1))));
    }

    /**
     * Fixture catalog.
     *
     * @param tasks tasks in the 24h window
     * @param outOfWindow tasks that must not join the window
     */
    public record Catalog(List<TaskFixture> tasks, List<TaskFixture> outOfWindow) {
        public Catalog {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            outOfWindow = outOfWindow == null ? List.of() : List.copyOf(outOfWindow);
        }
    }

    /**
     * One requirement task and its timeline.
     *
     * @param taskId task id
     * @param projectId project id
     * @param title operator title
     * @param status current status
     * @param acceptedAt first CREATED/accepted timestamp
     * @param terminalAt terminal timestamp; null if running
     * @param pullRequestUrl PR url or empty
     * @param errorCategory structured category or empty
     * @param statusEvents ordered status events, durationMs often 0
     * @param stageRuns role attempts
     * @param retrievals retrieval runs
     */
    public record TaskFixture(
            String taskId,
            String projectId,
            String title,
            String status,
            Instant acceptedAt,
            Instant terminalAt,
            String pullRequestUrl,
            String errorCategory,
            List<StatusEventFixture> statusEvents,
            List<StageRunFixture> stageRuns,
            List<RetrievalFixture> retrievals
    ) {
        public TaskFixture {
            statusEvents = statusEvents == null ? List.of() : List.copyOf(statusEvents);
            stageRuns = stageRuns == null ? List.of() : List.copyOf(stageRuns);
            retrievals = retrievals == null ? List.of() : List.copyOf(retrievals);
            pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl;
            errorCategory = errorCategory == null ? "" : errorCategory;
        }

        /**
         * @return true when the task has a terminal timestamp
         */
        public boolean terminal() {
            return terminalAt != null;
        }
    }

    /**
     * Task status ledger row.
     *
     * @param status status name
     * @param enteredAt enter time
     * @param durationMs stored duration, often 0 from host-finalizer
     */
    public record StatusEventFixture(String status, Instant enteredAt, long durationMs) {
    }

    /**
     * Role stage attempt.
     *
     * @param stageRunId stage run id
     * @param role role name
     * @param status stage status
     * @param attemptNo attempt number
     * @param startedAt start
     * @param finishedAt finish; null if running
     * @param providerAttempts provider attempts for this stage
     */
    public record StageRunFixture(
            String stageRunId,
            String role,
            String status,
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            List<ProviderAttemptFixture> providerAttempts
    ) {
        public StageRunFixture {
            providerAttempts = providerAttempts == null ? List.of() : List.copyOf(providerAttempts);
        }
    }

    /**
     * Provider usage row. {@code attemptId} is the immutable dedupe key.
     *
     * @param attemptId immutable attempt id
     * @param runtime runtime allowlist value
     * @param provider provider allowlist value
     * @param modelAlias model alias, never a Prometheus label
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     * @param cacheTokens cache tokens
     * @param estimatedCostCny estimated CNY as decimal string
     * @param usageAvailable whether usage was observed
     * @param errorCategory allowlisted category or blank
     */
    public record ProviderAttemptFixture(
            String attemptId,
            String runtime,
            String provider,
            String modelAlias,
            long inputTokens,
            long outputTokens,
            long cacheTokens,
            String estimatedCostCny,
            boolean usageAvailable,
            String errorCategory
    ) {
    }

    /**
     * Retrieval run.
     *
     * @param runId run id
     * @param status run status
     * @param durationMs run duration
     * @param iterations completed iterations
     * @param degraded degraded evidence
     * @param steps retrieval steps
     */
    public record RetrievalFixture(
            String runId,
            String status,
            long durationMs,
            int iterations,
            boolean degraded,
            List<RetrievalStepFixture> steps
    ) {
        public RetrievalFixture {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * Retrieval step.
     *
     * @param stepId step id
     * @param durationMs step duration
     */
    public record RetrievalStepFixture(String stepId, long durationMs) {
    }
}
