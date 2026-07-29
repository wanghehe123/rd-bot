package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;

import java.util.List;
import java.util.Optional;

/**
 * Persistent control-plane port for pre-registered coding benchmark trials and their leases.
 */
public interface CodingBenchmarkTrialStore {

    /**
     * Persists every logical trial and its initial event before campaign dispatch starts.
     *
     * @param campaignId parent evaluation campaign ID
     * @param trials complete immutable 88-trial plan
     */
    void createAll(String campaignId, List<CodingBenchmarkTrial> trials);

    /** @return a trial by its immutable logical ID, when one has been registered. */
    Optional<CodingBenchmarkTrial> find(String trialId);

    /** @return all registered trial snapshots for a campaign in deterministic creation order. */
    List<CodingBenchmarkTrial> listByCampaign(String campaignId);

    /**
     * Atomically claims one queued or expired-preparing trial for a worker.
     *
     * @param campaignId parent campaign to schedule
     * @param leaseOwner stable worker identity
     * @param now current epoch milliseconds
     * @param leaseMillis positive lease duration
     * @return one claimed trial, or empty when no candidate is available
     */
    Optional<CodingBenchmarkTrial> claimNext(String campaignId, String leaseOwner, long now, long leaseMillis);

    /**
     * Applies a version-checked state transition and appends its lifecycle evidence atomically.
     *
     * @param trialId immutable logical trial ID
     * @param expected expected source state
     * @param expectedVersion expected optimistic-lock version
     * @param target desired target state
     * @param verdict Oracle-owned current verdict
     * @param errorCategory stable error category, if any
     * @param errorMessage bounded diagnostic message, if any
     * @param now current epoch milliseconds
     * @return the updated trial snapshot
     */
    CodingBenchmarkTrial transition(
            String trialId,
            CodingBenchmarkTrialStatus expected,
            long expectedVersion,
            CodingBenchmarkTrialStatus target,
            CodingBenchmarkVerdict verdict,
            String errorCategory,
            String errorMessage,
            long now
    );

    /** @return append-only lifecycle evidence for one trial in timestamp order. */
    List<CodingBenchmarkTrialEvent> listEvents(String trialId);
}
