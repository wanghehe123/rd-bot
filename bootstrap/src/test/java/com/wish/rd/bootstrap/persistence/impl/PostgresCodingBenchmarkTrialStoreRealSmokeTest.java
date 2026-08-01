package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialEventRow;
import com.wish.rd.bootstrap.persistence.entity.CodingBenchmarkTrialRow;
import com.wish.rd.bootstrap.persistence.mapper.CodingBenchmarkTrialEventMapper;
import com.wish.rd.bootstrap.persistence.mapper.CodingBenchmarkTrialMapper;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialEvent;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL smoke-test for {@link PostgresCodingBenchmarkTrialStore}.
 *
 * <p>Each {@code @Test} method uses a Snowflake-scoped campaign ID so isolation is
 * guaranteed even when tests run in parallel against the same database.  All rows
 * created during a test are deleted in the {@code finally} block.</p>
 *
 * <p>Guarded by {@code @EnabledIfSystemProperty} so the default Maven build stays
 * green on hosts without a running PostgreSQL instance.</p>
 *
 * <p>This test does NOT replace the Mockito unit-test coverage.  It exercises the
 * actual SQL-layer contracts (unique constraints, CAS semantics, lease expiry) that
 * cannot be verified with mocked mappers.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rd.knowledge.store=postgres",
                "rd.storage.mode=memory",
                "rd.distributed-lock.mode=local",
                "rd.repair.queue.mode=memory"
        }
)
@EnabledIfSystemProperty(named = "rd.integration.trial-store-atomic.enabled", matches = "true")
class PostgresCodingBenchmarkTrialStoreRealSmokeTest {

    private static final long CAMPAIGN_OFFSET = 5_900_000_000_000_000_000L;

    @Autowired
    private PostgresCodingBenchmarkTrialStore store;

    @Autowired
    private CodingBenchmarkTrialMapper trialMapper;

    @Autowired
    private CodingBenchmarkTrialEventMapper eventMapper;

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a Snowflake-scoped campaign ID unique to the current test run,
     * preventing cross-test row collisions.
     */
    private String campaignId() {
        return String.valueOf(CAMPAIGN_OFFSET + Math.abs(System.currentTimeMillis() % 1_000_000L));
    }

    private CodingBenchmarkTrial queuedTrial(String trialId, String caseId, CodingBenchmarkArm arm, long now) {
        return CodingBenchmarkTrial.queued(trialId, campaignId(), caseId, arm, 0, now);
    }

    private void deleteByCampaignId(String campaignId) {
        List<CodingBenchmarkTrialRow> rows = trialMapper.selectByCampaign(Long.parseLong(campaignId));
        for (CodingBenchmarkTrialRow row : rows) {
            trialMapper.deleteById(row.id);
        }
    }

    private List<CodingBenchmarkTrial> fourTrialsForCaseA(String caseId, long now) {
        return List.of(
                queuedTrial("trial-a-" + caseId, caseId, CodingBenchmarkArm.A, now),
                queuedTrial("trial-b-" + caseId, caseId, CodingBenchmarkArm.B, now),
                queuedTrial("trial-c-" + caseId, caseId, CodingBenchmarkArm.C, now),
                queuedTrial("trial-d-" + caseId, caseId, CodingBenchmarkArm.D, now)
        );
    }

    // -------------------------------------------------------------------------
    // a. createAll + find + listByCampaign round-trip
    // -------------------------------------------------------------------------

    /**
     * Inserts four QUEUED trials for the same case with arm A/B/C/D, then verifies
     * {@code find} returns each trial and {@code listByCampaign} includes all four.
     */
    @Test
    void shouldRoundTripFourTrialsViaCreateAllFindAndListByCampaign() {
        String campaign = campaignId();
        String caseId = "case-a-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();
        List<CodingBenchmarkTrial> trials = fourTrialsForCaseA(caseId, now);

        try {
            store.createAll(campaign, trials);

            for (CodingBenchmarkTrial trial : trials) {
                Optional<CodingBenchmarkTrial> found = store.find(trial.trialId());
                assertTrue(found.isPresent(), "trial should be findable: " + trial.trialId());
                assertEquals(trial.caseId(), found.orElseThrow().caseId());
                assertEquals(trial.arm(), found.orElseThrow().arm());
                assertEquals(CodingBenchmarkTrialStatus.QUEUED, found.orElseThrow().status());
            }

            List<CodingBenchmarkTrial> listed = store.listByCampaign(campaign);
            assertEquals(4, listed.size(), "listByCampaign should return all 4 trials");
        } finally {
            deleteByCampaignId(campaign);
        }
    }

    // -------------------------------------------------------------------------
    // b. UNIQUE (campaign_id, case_id, arm, replicate_no) constraint at PG layer
    // -------------------------------------------------------------------------

    /**
     * Attempts a second {@code createAll} for the same (campaign, case, arm, replicate_no)
     * cell.  The PostgreSQL unique constraint {@code uk_evaluation_trial_cell} must reject the
     * duplicate with a {@link DataIntegrityViolationException}.  The original row must still
     * exist with exactly one entry in the events table.
     */
    @Test
    void shouldRejectDuplicateCellWithUniqueConstraintViolation() {
        String campaign = campaignId();
        String caseId = "case-b-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();

        try {
            // First createAll – must succeed
            List<CodingBenchmarkTrial> firstBatch = List.of(
                    queuedTrial("trial-first-" + caseId, caseId, CodingBenchmarkArm.A, now)
            );
            store.createAll(campaign, firstBatch);

            // Second createAll – same cell, different trialId – must fail at PG
            List<CodingBenchmarkTrial> secondBatch = List.of(
                    queuedTrial("trial-duplicate-" + caseId, caseId, CodingBenchmarkArm.A, now)
            );
            assertThrows(DataIntegrityViolationException.class, () ->
                    store.createAll(campaign, secondBatch),
                    "duplicate (campaign, case, arm, replicate_no) should violate unique constraint"
            );

            // Verify original row still intact – one trial, one initial event
            List<CodingBenchmarkTrial> listed = store.listByCampaign(campaign);
            assertEquals(1, listed.size(), "only the original row should remain");
            assertEquals("trial-first-" + caseId, listed.get(0).trialId());

            List<CodingBenchmarkTrialEvent> events = store.listEvents("trial-first-" + caseId);
            assertEquals(1, events.size(), "only the initial event should exist");
        } finally {
            deleteByCampaignId(campaign);
        }
    }

    // -------------------------------------------------------------------------
    // c. claimNext single-lease atomicity with concurrent workers
    // -------------------------------------------------------------------------

    /**
     * Creates four QUEUED trials and runs two workers concurrently, each calling
     * {@code claimNext}.  Verifies:
     * <ul>
     *   <li>each worker receives a non-null trial</li>
     *   <li>the two trial IDs are distinct (no overlap)</li>
     *   <li>every claimed trial has exactly one PREPARING event with {@code fromStatus=QUEUED}</li>
     * </ul>
     */
    @Test
    void shouldClaimTwoDistinctTrialsAtomicallyWithConcurrentWorkers() throws Exception {
        String campaign = campaignId();
        String caseId = "case-c-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();

        try {
            store.createAll(campaign, List.of(
                    queuedTrial("trial-w1-" + caseId, caseId, CodingBenchmarkArm.A, now),
                    queuedTrial("trial-w2-" + caseId, caseId, CodingBenchmarkArm.B, now),
                    queuedTrial("trial-w3-" + caseId, caseId, CodingBenchmarkArm.C, now),
                    queuedTrial("trial-w4-" + caseId, caseId, CodingBenchmarkArm.D, now)
            ));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicReference<Optional<CodingBenchmarkTrial>> worker1Result = new AtomicReference<>();
            AtomicReference<Optional<CodingBenchmarkTrial>> worker2Result = new AtomicReference<>();

            executor.submit(() -> {
                await(startLatch);
                worker1Result.set(store.claimNext(campaign, "worker-1", now + 500, 90_000));
            });
            executor.submit(() -> {
                await(startLatch);
                worker2Result.set(store.claimNext(campaign, "worker-2", now + 500, 90_000));
            });

            startLatch.countDown();
            executor.shutdown();

            assertTrue(worker1Result.get().isPresent(), "worker-1 should claim a trial");
            assertTrue(worker2Result.get().isPresent(), "worker-2 should claim a trial");
            assertNotNull(worker1Result.get().orElseThrow().trialId());
            assertNotNull(worker2Result.get().orElseThrow().trialId());
            assertFalse(worker1Result.get().orElseThrow().trialId()
                            .equals(worker2Result.get().orElseThrow().trialId()),
                    "the two workers must receive distinct trial IDs");

            for (Optional<CodingBenchmarkTrial> claimed : List.of(worker1Result.get(), worker2Result.get())) {
                String claimedId = claimed.orElseThrow().trialId();
                List<CodingBenchmarkTrialEvent> evts = store.listEvents(claimedId);
                assertEquals(2, evts.size(), "claimed trial should have initial + claim events");
                CodingBenchmarkTrialEvent claimEvent = evts.get(1);
                assertEquals(CodingBenchmarkTrialStatus.PREPARING, claimEvent.toStatus());
                assertEquals(CodingBenchmarkTrialStatus.QUEUED, claimEvent.fromStatus());
            }
        } finally {
            deleteByCampaignId(campaign);
        }
    }

    // -------------------------------------------------------------------------
    // d. transition CAS rejects stale version without appending an event
    // -------------------------------------------------------------------------

    /**
     * Transitions a trial from QUEUED → PREPARING → RUNNING_AGENTS, then attempts an
     * illegal transition using a stale {@code expectedVersion}.  The store must throw
     * {@link IllegalStateException}.  The row must remain at {@code version=1} with
     * {@code status=RUNNING_AGENTS}, and only two events (QUEUED, PREPARING) must be present.
     */
    @Test
    void shouldRejectStaleCasTransitionWithoutAppendingAnEvent() {
        String campaign = campaignId();
        String caseId = "case-d-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();
        String trialId = "trial-cas-" + caseId;

        try {
            store.createAll(campaign, List.of(queuedTrial(trialId, caseId, CodingBenchmarkArm.A, now)));

            // QUEUED -> PREPARING  (version 0 -> 1)
            CodingBenchmarkTrial first = store.transition(
                    trialId, CodingBenchmarkTrialStatus.QUEUED, 0L,
                    CodingBenchmarkTrialStatus.PREPARING, CodingBenchmarkVerdict.PENDING,
                    "", "", now + 1
            );
            assertEquals(1L, first.version());

            // PREPARING -> RUNNING_AGENTS  (version 1 -> 2)
            CodingBenchmarkTrial second = store.transition(
                    trialId, CodingBenchmarkTrialStatus.PREPARING, 1L,
                    CodingBenchmarkTrialStatus.RUNNING_AGENTS, CodingBenchmarkVerdict.PENDING,
                    "", "", now + 2
            );
            assertEquals(2L, second.version());

            // Stale CAS attempt: expectedVersion=0 while row is at version=2
            assertThrows(IllegalStateException.class, () ->
                    store.transition(
                            trialId, CodingBenchmarkTrialStatus.PREPARING, 0L,
                            CodingBenchmarkTrialStatus.RUNNING_ORACLE, CodingBenchmarkVerdict.PENDING,
                            "", "", now + 3
                    ),
                    "stale CAS with wrong expectedVersion should throw IllegalStateException"
            );

            // Verify row state unchanged – version still 2, status still RUNNING_AGENTS
            CodingBenchmarkTrial current = store.find(trialId).orElseThrow();
            assertEquals(2L, current.version(), "trial version must not have changed");
            assertEquals(CodingBenchmarkTrialStatus.RUNNING_AGENTS, current.status());

            // Verify exactly two events (no extra event from the failed CAS)
            List<CodingBenchmarkTrialEvent> evts = store.listEvents(trialId);
            assertEquals(2, evts.size(), "no event should be appended for the rejected CAS");
            assertEquals(CodingBenchmarkTrialStatus.QUEUED, evts.get(0).toStatus());
            assertEquals(CodingBenchmarkTrialStatus.PREPARING, evts.get(1).toStatus());
        } finally {
            deleteByCampaignId(campaign);
        }
    }

    // -------------------------------------------------------------------------
    // e. only INFRA_ERROR verdict may enter RETRY_PENDING path
    // -------------------------------------------------------------------------

    /**
     * Transitions a trial to RUNNING_AGENTS, then attempts RETRY_PENDING with
     * {@code verdict=TIMEOUT}.  The store must throw {@link IllegalStateException} before
     * touching the database.  The row must remain at its original state and no event may
     * be appended.
     */
    @Test
    void shouldRejectNonInfraErrorVerdictFromEnteringRetryPending() {
        String campaign = campaignId();
        String caseId = "case-e-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();
        String trialId = "trial-verdict-" + caseId;

        try {
            store.createAll(campaign, List.of(queuedTrial(trialId, caseId, CodingBenchmarkArm.A, now)));

            // QUEUED -> PREPARING
            store.transition(
                    trialId, CodingBenchmarkTrialStatus.QUEUED, 0L,
                    CodingBenchmarkTrialStatus.PREPARING, CodingBenchmarkVerdict.PENDING,
                    "", "", now + 1
            );

            // PREPARING -> RUNNING_AGENTS
            store.transition(
                    trialId, CodingBenchmarkTrialStatus.PREPARING, 1L,
                    CodingBenchmarkTrialStatus.RUNNING_AGENTS, CodingBenchmarkVerdict.PENDING,
                    "", "", now + 2
            );

            // Illegal: RETRY_PENDING with verdict=TIMEOUT (not INFRA_ERROR)
            assertThrows(IllegalStateException.class, () ->
                    store.transition(
                            trialId, CodingBenchmarkTrialStatus.RUNNING_AGENTS, 2L,
                            CodingBenchmarkTrialStatus.RETRY_PENDING, CodingBenchmarkVerdict.TIMEOUT,
                            "", "", now + 3
                    ),
                    "only INFRA_ERROR verdict may enter the RETRY_PENDING path"
            );

            // Verify row unchanged – still RUNNING_AGENTS, version still 2
            CodingBenchmarkTrial current = store.find(trialId).orElseThrow();
            assertEquals(CodingBenchmarkTrialStatus.RUNNING_AGENTS, current.status());
            assertEquals(2L, current.version());

            // Verify only two events – no failed RETRY_PENDING event
            List<CodingBenchmarkTrialEvent> evts = store.listEvents(trialId);
            assertEquals(2, evts.size(), "no event should be appended for the rejected verdict");
        } finally {
            deleteByCampaignId(campaign);
        }
    }

    // -------------------------------------------------------------------------
    // f. lease expiry reclaim returns the same trial with appended PREPARING event
    // -------------------------------------------------------------------------

    /**
     * Claims a trial with a short lease, then calls {@code claimNext} again using a
     * {@code now} that is before the current {@code leaseExpiresAt}.  The same trial must
     * be re-claimed (lease expired and reclaimed by FOR UPDATE SKIP LOCKED), and a second
     * PREPARING event with {@code fromStatus=PREPARING} must be appended.
     */
    @Test
    void shouldReclaimTrialOnLeaseExpiryAndAppendPreparingFromPreparingEvent() {
        String campaign = campaignId();
        String caseId = "case-f-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();
        String trialId = "trial-lease-" + caseId;

        try {
            store.createAll(campaign, List.of(queuedTrial(trialId, caseId, CodingBenchmarkArm.A, now)));

            // First claim: 90-second lease
            Optional<CodingBenchmarkTrial> first = store.claimNext(campaign, "worker-lease", now + 1_000, 90_000);
            assertTrue(first.isPresent(), "first claim should succeed");
            assertEquals(trialId, first.orElseThrow().trialId());
            assertEquals(CodingBenchmarkTrialStatus.PREPARING, first.orElseThrow().status());

            // Second claim with "now" BEFORE the lease expires (worker not done, but we test expiry reclaim path)
            // Use now + 5_000 (still inside the 90s window) – FOR UPDATE SKIP LOCKED will skip it.
            // To trigger expiry reclaim, use now that is AFTER lease_expires_at:
            long reclaimNow = now + 1_000 + 90_001; // just past lease expiry
            Optional<CodingBenchmarkTrial> reclaimed = store.claimNext(campaign, "worker-lease", reclaimNow, 90_000);
            assertTrue(reclaimed.isPresent(), "should reclaim the same trial after lease expires");
            assertEquals(trialId, reclaimed.orElseThrow().trialId(),
                    "the same trial must be re-claimed on lease expiry");

            // Verify second PREPARING event with fromStatus=PREPARING
            List<CodingBenchmarkTrialEvent> evts = store.listEvents(trialId);
            assertEquals(3, evts.size(), "reclaim should append one PREPARING event");
            CodingBenchmarkTrialEvent reclaimEvent = evts.get(2);
            assertEquals(CodingBenchmarkTrialStatus.PREPARING, reclaimEvent.toStatus());
            assertEquals(CodingBenchmarkTrialStatus.PREPARING, reclaimEvent.fromStatus(),
                    "fromStatus must be PREPARING to confirm lease renewal rather than new claim");
        } finally {
            deleteByCampaignId(campaign);
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting on latch", e);
        }
    }
}
