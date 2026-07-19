package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunEvent;
import com.wish.rd.engine.evaluation.model.EvaluationRunPage;
import com.wish.rd.engine.evaluation.model.EvaluationRunQuery;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Orchestrates persistent Web-triggered evaluation attempts over a local execution port. */
@Service
public final class EvaluationRunEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationRunEngine.class);

    private final EvaluationRunStore store;
    private final EvaluationExecutionPort executionPort;
    private final EvaluationTaskSchedulerPort scheduler;
    private final Supplier<String> idSupplier;
    private final LongSupplier clock;

    @Autowired
    public EvaluationRunEngine(
            EvaluationRunStore store,
            EvaluationExecutionPort executionPort,
            EvaluationTaskSchedulerPort scheduler,
            SnowflakeIdGenerator idGenerator
    ) {
        this(store, executionPort, scheduler,
                (idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator)::nextIdString,
                System::currentTimeMillis);
    }

    public EvaluationRunEngine(
            EvaluationRunStore store,
            EvaluationExecutionPort executionPort,
            EvaluationTaskSchedulerPort scheduler,
            Supplier<String> idSupplier,
            LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Creates and asynchronously queues a new evaluation attempt. */
    public EvaluationRun start(EvaluationRunConfig config) {
        return startAttempt(config, 1, "");
    }

    /** Returns one current run snapshot or throws when it does not exist. */
    public EvaluationRun find(String runId) {
        return store.find(runId).orElseThrow(() -> new NoSuchElementException("evaluation run not found: " + runId));
    }

    /** Returns all evaluation attempts ordered by newest creation time. */
    public List<EvaluationRun> list() {
        return store.list();
    }

    /** Returns one filtered, server-produced history page. */
    public EvaluationRunPage query(EvaluationRunQuery query) {
        return store.query(query);
    }

    /** Returns the append-only lifecycle timeline. */
    public List<EvaluationRunEvent> timeline(String runId) {
        find(runId);
        return store.listEvents(runId);
    }

    /** Returns artifact metadata emitted by the local runner. */
    public List<EvaluationArtifact> artifacts(String runId) {
        find(runId);
        return store.listArtifacts(runId);
    }

    /** Requests cancellation and returns the immutable cancelled snapshot. */
    public EvaluationRun cancel(String runId) {
        EvaluationRun current = find(runId);
        if (!current.status().isActive()) {
            throw new IllegalStateException("evaluation run is not cancellable: " + current.status());
        }
        long now = now();
        EvaluationRun requested = store.transition(runId, current.status(), EvaluationRunStatus.CANCEL_REQUESTED,
                "cancellation requested", "", "", now);
        executionPort.cancel(runId);
        EvaluationRun latest = find(runId);
        EvaluationRun cancelled;
        if (latest.status() == EvaluationRunStatus.CANCELLED) {
            cancelled = latest;
        } else {
            try {
                cancelled = store.transition(runId, requested.status(), EvaluationRunStatus.CANCELLED,
                        "evaluation cancelled", "", "", now());
            } catch (IllegalStateException conflict) {
                EvaluationRun concurrent = find(runId);
                if (concurrent.status() != EvaluationRunStatus.CANCELLED) {
                    throw conflict;
                }
                cancelled = concurrent;
            }
        }
        LOGGER.info("[EVALUATION] CANCELLED runId={} attempt={}", runId, cancelled.attemptNo());
        return cancelled;
    }

    /** Creates a new parent-linked attempt for a terminal run. */
    public EvaluationRun retry(String runId) {
        EvaluationRun previous = find(runId);
        if (!previous.status().isTerminal()) {
            throw new IllegalStateException("only terminal evaluation runs can be retried");
        }
        return startAttempt(previous.config(), previous.attemptNo() + 1, previous.runId());
    }

    private EvaluationRun startAttempt(EvaluationRunConfig config, int attemptNo, String parentRunId) {
        Objects.requireNonNull(config, "config must not be null").validate();
        if (!config.baselineRunId().isBlank()) {
            EvaluationRun baseline = find(config.baselineRunId());
            if (baseline.status() != EvaluationRunStatus.SUCCEEDED) {
                throw new IllegalArgumentException("baseline evaluation run must be successful");
            }
        }
        EvaluationRun created = store.create(EvaluationRun.created(nextId(), config, attemptNo, parentRunId, now()));
        EvaluationRun queued = store.transition(created.runId(), EvaluationRunStatus.CREATED, EvaluationRunStatus.QUEUED,
                "queued for local execution", "", "", now());
        scheduler.submit(() -> execute(queued.runId()));
        LOGGER.info("[EVALUATION] QUEUED runId={} attempt={} source={} dataset={} judge={}",
                queued.runId(), queued.attemptNo(), config.source(), config.datasetId(), config.judgeProvider());
        return queued;
    }

    private void execute(String runId) {
        EvaluationRun current = find(runId);
        if (current.status().isTerminal()) {
            return;
        }
        try {
            current = store.transition(runId, EvaluationRunStatus.QUEUED, EvaluationRunStatus.RECORDING,
                    "record evaluation samples", "", "", now());
            EvaluationExecutionResult result = executionPort.execute(current, (phase, message) -> advance(runId, phase, message));
            EvaluationRun latest = find(runId);
            if (latest.status().isTerminal()) {
                return;
            }
            store.appendArtifacts(runId, result.artifacts());
            EvaluationRun completed = store.complete(runId, latest.status(), result, now());
            LOGGER.info("[EVALUATION] SUCCEEDED runId={} samples={} passed={} failed={} gatesPassed={}",
                    runId, completed.sampleCount(), completed.passedSampleCount(), completed.failedSampleCount(),
                    completed.overallPassed());
        } catch (CancellationException exception) {
            finishCancelled(runId);
        } catch (RuntimeException exception) {
            finishFailed(runId, exception);
        }
    }

    private void finishFailed(String runId, RuntimeException exception) {
        EvaluationRun latest = find(runId);
        if (latest.status() == EvaluationRunStatus.CANCEL_REQUESTED) {
            finishCancelled(runId);
            return;
        }
        if (latest.status().isTerminal()) {
            return;
        }
        try {
            store.transition(runId, latest.status(), EvaluationRunStatus.FAILED,
                    "local evaluation failed", exception.getClass().getSimpleName(), safe(exception.getMessage()), now());
        } catch (IllegalStateException conflict) {
            EvaluationRun concurrent = find(runId);
            if (concurrent.status() == EvaluationRunStatus.CANCEL_REQUESTED) {
                finishCancelled(runId);
                return;
            }
            if (concurrent.status().isTerminal()) {
                return;
            }
            throw conflict;
        }
        LOGGER.warn("[EVALUATION] FAILED runId={} category={} message={}",
                runId, exception.getClass().getSimpleName(), safe(exception.getMessage()));
    }

    private void advance(String runId, EvaluationRunStatus target, String message) {
        EvaluationRun current = find(runId);
        if (current.status().isTerminal() || current.status() == EvaluationRunStatus.CANCEL_REQUESTED) {
            throw new CancellationException("evaluation was cancelled");
        }
        store.transition(runId, current.status(), target, safe(message), "", "", now());
        LOGGER.info("[EVALUATION] PHASE runId={} status={} message={}", runId, target, safe(message));
    }

    private void finishCancelled(String runId) {
        EvaluationRun current = find(runId);
        if (current.status().isTerminal()) {
            return;
        }
        EvaluationRun requested;
        if (current.status() == EvaluationRunStatus.CANCEL_REQUESTED) {
            requested = current;
        } else {
            try {
                requested = store.transition(runId, current.status(), EvaluationRunStatus.CANCEL_REQUESTED,
                        "cancellation observed", "", "", now());
            } catch (IllegalStateException conflict) {
                EvaluationRun concurrent = find(runId);
                if (concurrent.status().isTerminal()) {
                    return;
                }
                if (concurrent.status() != EvaluationRunStatus.CANCEL_REQUESTED) {
                    throw conflict;
                }
                requested = concurrent;
            }
        }
        try {
            store.transition(runId, requested.status(), EvaluationRunStatus.CANCELLED,
                    "evaluation cancelled", "", "", now());
        } catch (IllegalStateException conflict) {
            if (find(runId).status() != EvaluationRunStatus.CANCELLED) {
                throw conflict;
            }
        }
    }

    private String nextId() {
        String id = idSupplier.get();
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalStateException("evaluation id supplier returned an unsafe id");
        }
        return id;
    }

    private long now() {
        return clock.getAsLong();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 1_000 ? normalized : normalized.substring(0, 1_000);
    }
}
