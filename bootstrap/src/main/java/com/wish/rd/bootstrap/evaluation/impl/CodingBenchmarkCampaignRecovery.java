package com.wish.rd.bootstrap.evaluation.impl;

import com.wish.rd.engine.evaluation.EvaluationRunStore;
import com.wish.rd.engine.evaluation.model.EvaluationMode;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Re-kicks the coding-benchmark dispatcher after process restart for campaigns that were
 * mid-flight when the JVM stopped.
 *
 * <p>Lease-expired {@code PREPARING} trials remain reclaimable via existing {@code claimNext}
 * SQL; this component only restarts the dispatch loop for eligible parent runs.</p>
 */
@Component
public final class CodingBenchmarkCampaignRecovery {

    private static final Logger log = LoggerFactory.getLogger(CodingBenchmarkCampaignRecovery.class);

    private final EvaluationRunStore runStore;
    private final CodingBenchmarkDispatcher dispatcher;

    public CodingBenchmarkCampaignRecovery(
            EvaluationRunStore runStore,
            CodingBenchmarkDispatcher dispatcher
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    }

    /**
     * Scans persisted runs and re-kicks dispatch for active, unpaused coding-benchmark campaigns.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        List<EvaluationRun> candidates = runStore.list().stream()
                .filter(this::isRecoverable)
                .toList();
        if (candidates.isEmpty()) {
            log.debug("[CODING_BENCHMARK] recovery found no active campaigns to resume");
            return;
        }
        log.info("[CODING_BENCHMARK] recovery re-kicking {} active campaign(s)", candidates.size());
        for (EvaluationRun run : candidates) {
            String snapshotId = CodingBenchmarkCampaignService.snapshotIdFrom(run);
            log.info("[CODING_BENCHMARK] recovery kick runId={} status={} snapshotId={}",
                    run.runId(), run.status(), snapshotId);
            dispatcher.kick(run.runId(), snapshotId);
        }
    }

    private boolean isRecoverable(EvaluationRun run) {
        if (run.config().mode() != EvaluationMode.CODING_BENCHMARK) {
            return false;
        }
        if (run.status().isTerminal() || run.dispatchPaused()) {
            return false;
        }
        return run.status() == EvaluationRunStatus.RUNNING_TRIALS
                || run.status() == EvaluationRunStatus.PREPARING;
    }
}
