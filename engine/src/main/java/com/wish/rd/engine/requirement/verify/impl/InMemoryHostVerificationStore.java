package com.wish.rd.engine.requirement.verify.impl;

import com.wish.rd.engine.requirement.verify.HostVerificationStore;
import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStepName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Test-only in-memory host verification store with CAS-like transition checks.
 *
 * <p>Not a production truth source. Use {@code PostgresHostVerificationStore}
 * when {@code rd.knowledge.store=postgres}.
 */
public final class InMemoryHostVerificationStore implements HostVerificationStore {

    private final Map<String, HostVerificationRun> runs = new LinkedHashMap<>();
    private final Map<String, EnumMap<HostVerificationStepName, HostVerificationStep>> steps = new LinkedHashMap<>();
    private final Map<String, List<HostVerificationArtifact>> artifacts = new LinkedHashMap<>();

    @Override
    public synchronized HostVerificationRun create(HostVerificationRun run) {
        if (run == null) {
            throw new IllegalArgumentException("host verification run must not be null");
        }
        if (runs.containsKey(run.runId())) {
            throw new IllegalStateException("host verification run already exists: " + run.runId());
        }
        boolean duplicateAttempt = runs.values().stream().anyMatch(existing ->
                existing.taskId().equals(run.taskId()) && existing.attemptNo() == run.attemptNo());
        if (duplicateAttempt) {
            throw new IllegalStateException(
                    "host verification attempt already exists: " + run.taskId() + "#" + run.attemptNo());
        }
        runs.put(run.runId(), run);
        steps.put(run.runId(), new EnumMap<>(HostVerificationStepName.class));
        artifacts.put(run.runId(), new ArrayList<>());
        return run;
    }

    @Override
    public synchronized Optional<HostVerificationRun> find(String runId) {
        return Optional.ofNullable(runs.get(normalize(runId)));
    }

    @Override
    public synchronized List<HostVerificationRun> listByTask(String taskId) {
        String normalized = normalize(taskId);
        return runs.values().stream()
                .filter(run -> run.taskId().equals(normalized))
                .sorted(Comparator.comparingInt(HostVerificationRun::attemptNo))
                .toList();
    }

    @Override
    public synchronized HostVerificationRun transition(
            String runId,
            HostVerificationStatus expected,
            HostVerificationStatus target,
            String failureCategory,
            String errorMessage,
            long nowEpochMillis
    ) {
        HostVerificationRun current = require(runId);
        requireExpectedAndAllowed(current, expected, target);
        HostVerificationRun updated = current.withStatus(target, failureCategory, errorMessage, nowEpochMillis);
        runs.put(updated.runId(), updated);
        return updated;
    }

    @Override
    public synchronized void saveStep(HostVerificationStep step) {
        if (step == null) {
            throw new IllegalArgumentException("host verification step must not be null");
        }
        require(step.runId());
        steps.get(step.runId()).put(step.step(), step);
    }

    @Override
    public synchronized List<HostVerificationStep> listSteps(String runId) {
        require(runId);
        return List.copyOf(steps.get(normalize(runId)).values());
    }

    @Override
    public synchronized HostVerificationArtifact appendArtifact(HostVerificationArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("host verification artifact must not be null");
        }
        require(artifact.runId());
        List<HostVerificationArtifact> stored = artifacts.get(artifact.runId());
        boolean duplicate = stored.stream().anyMatch(existing ->
                existing.artifactId().equals(artifact.artifactId())
                        || existing.relativePath().equals(artifact.relativePath()));
        if (duplicate) {
            throw new IllegalStateException(
                    "host verification artifact already exists: " + artifact.runId() + "/" + artifact.relativePath());
        }
        stored.add(artifact);
        return artifact;
    }

    @Override
    public synchronized List<HostVerificationArtifact> listArtifacts(String runId) {
        require(runId);
        return List.copyOf(artifacts.get(normalize(runId)));
    }

    private HostVerificationRun require(String runId) {
        HostVerificationRun run = runs.get(normalize(runId));
        if (run == null) {
            throw new NoSuchElementException("host verification run not found: " + runId);
        }
        return run;
    }

    private static void requireExpectedAndAllowed(
            HostVerificationRun current,
            HostVerificationStatus expected,
            HostVerificationStatus target
    ) {
        if (current.status() != expected) {
            throw new IllegalStateException("stale host verification status: expected " + expected
                    + " but was " + current.status());
        }
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal host verification run is immutable: " + current.runId());
        }
        if (!current.status().canTransitionTo(target)) {
            throw new IllegalStateException(
                    "illegal host verification transition: " + current.status() + " -> " + target);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
