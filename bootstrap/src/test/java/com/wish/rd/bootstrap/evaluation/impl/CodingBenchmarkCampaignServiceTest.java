package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.impl.InMemoryEvaluationRunStore;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkArm;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrialStatus;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkVerdict;
import com.wish.rd.engine.evaluation.model.EvaluationMode;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunConfig;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingBenchmarkCampaignServiceTest {

    private static final String SNAPSHOT_ID = "probe-snap";
    private static final List<String> REFERENCED_MANIFESTS = List.of(
            "dataset-manifest.json",
            "environment-manifest.json",
            "knowledge-manifest.json",
            "analysis-plan.json",
            "readiness-report.json",
            "runtime-manifest.json"
    );

    @TempDir
    Path tempDir;

    private InMemoryEvaluationRunStore runStore;
    private CodingBenchmarkDispatcherTest.RecordingTrialStore trialStore;
    private KickRecordingDispatcher dispatcher;
    private FakeCodingBenchmarkExecutionPort fakePort;
    private CodingBenchmarkCampaignService service;
    private FileSystemCodingBenchmarkCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        runStore = new InMemoryEvaluationRunStore();
        trialStore = new CodingBenchmarkDispatcherTest.RecordingTrialStore();
        fakePort = new FakeCodingBenchmarkExecutionPort(5L);
        SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator();

        EvaluationProperties properties = new EvaluationProperties();
        properties.setRepositoryRoot(tempDir);
        properties.setCodingBenchmarkRoot(tempDir.resolve("snapshots"));
        catalog = new FileSystemCodingBenchmarkCatalog(properties, new ObjectMapper());

        writeSnapshotWithCases(SNAPSHOT_ID);

        dispatcher = new KickRecordingDispatcher();
        service = new CodingBenchmarkCampaignService(
                catalog, trialStore, runStore, properties, dispatcher, fakePort, idGenerator);
    }

    @Test
    void createProbeAdvancesRunToRunningTrialsAndKicksDispatcher() {
        EvaluationRun run = service.createProbeCampaign(SNAPSHOT_ID, "probe-test");

        assertEquals(EvaluationRunStatus.RUNNING_TRIALS, run.status());
        assertEquals(SNAPSHOT_ID, run.config().snapshotId());
        assertEquals(8, trialStore.listByCampaign(run.runId()).size());
        assertEquals(1, dispatcher.kicks.size());
        assertEquals(run.runId(), dispatcher.kicks.getFirst().campaignId());
        assertEquals(SNAPSHOT_ID, dispatcher.kicks.getFirst().snapshotId());
    }

    @Test
    void listCampaignsAndTrialsExposeHistoryBoard() {
        EvaluationRun run = service.createProbeCampaign(SNAPSHOT_ID, "probe-history");

        assertEquals(1, service.listCampaigns(10).size());
        assertEquals(run.runId(), service.listCampaigns(10).getFirst().runId());
        assertEquals(8, service.listTrials(run.runId()).size());
        assertTrue(service.findCampaign(run.runId()).isPresent());
    }

    @Test
    void pauseSetsDispatchPausedWithoutChangingStatus() {
        EvaluationRun seeded = seedRunningCampaign("9001", false);
        EvaluationRun paused = service.pause(seeded.runId());

        assertTrue(paused.dispatchPaused());
        assertEquals(EvaluationRunStatus.RUNNING_TRIALS, paused.status());
        assertEquals(seeded.version() + 1L, paused.version());
    }

    @Test
    void resumeClearsFlagAndKicksDispatcher() {
        EvaluationRun seeded = seedRunningCampaign("9002", true);
        dispatcher.kicks.clear();
        EvaluationRun resumed = service.resume(seeded.runId());

        assertFalse(resumed.dispatchPaused());
        assertEquals(1, dispatcher.kicks.size());
        assertEquals(seeded.runId(), dispatcher.kicks.getFirst().campaignId());
        assertEquals(SNAPSHOT_ID, dispatcher.kicks.getFirst().snapshotId());
    }

    @Test
    void cancelMarksRunAndNonTerminalTrialsCancelled() {
        EvaluationRun seeded = seedRunningCampaign("9003", false);
        trialStore.createAll(seeded.runId(), List.of(
                queued("trial-q", "case-01", CodingBenchmarkArm.A),
                active("trial-active", "case-02", CodingBenchmarkTrialStatus.RUNNING_AGENTS),
                succeeded("trial-done", "case-03")
        ));

        EvaluationRun cancelled = service.cancel(seeded.runId());

        assertEquals(EvaluationRunStatus.CANCELLED, cancelled.status());
        assertEquals(CodingBenchmarkTrialStatus.CANCELLED,
                trialStore.find("trial-q").orElseThrow().status());
        assertEquals(CodingBenchmarkTrialStatus.CANCELLED,
                trialStore.find("trial-active").orElseThrow().status());
        assertEquals(CodingBenchmarkTrialStatus.SUCCEEDED,
                trialStore.find("trial-done").orElseThrow().status());
        assertTrue(fakePort.isCampaignCancelled(seeded.runId()));
    }

    @Test
    void onTrialsDrainedAdvancesRunToSucceeded() {
        EvaluationRun seeded = seedRunningCampaign("9004", false);
        runStore.updateSampleProgress(seeded.runId(), 2, 2, 0, System.currentTimeMillis());

        service.onTrialsDrained(seeded.runId());

        EvaluationRun finished = runStore.find(seeded.runId()).orElseThrow();
        assertEquals(EvaluationRunStatus.SUCCEEDED, finished.status());
        assertEquals(2, finished.sampleCount());
        assertTrue(finished.overallPassed());
    }

    private EvaluationRun seedRunningCampaign(String runId, boolean paused) {
        long now = System.currentTimeMillis();
        EvaluationRunConfig config = new EvaluationRunConfig(
                "probe", SNAPSHOT_ID + ".json", null, SNAPSHOT_ID, 0, "", "", 3600,
                null, 0, false, "", "", EvaluationMode.CODING_BENCHMARK, SNAPSHOT_ID);
        EvaluationRun run = EvaluationRun.created(runId, config, 1, "", now)
                .withStatus(EvaluationRunStatus.RUNNING_TRIALS, "running trials", "", "", now);
        if (paused) {
            run = run.withDispatchPaused(true, now);
        }
        runStore.create(run);
        return run;
    }

    private static CodingBenchmarkTrial queued(String trialId, String caseId, CodingBenchmarkArm arm) {
        return CodingBenchmarkTrial.queued(trialId, "9003", caseId, arm, 0, 1_000L);
    }

    private static CodingBenchmarkTrial active(String trialId, String caseId, CodingBenchmarkTrialStatus status) {
        return new CodingBenchmarkTrial(
                trialId, "9003", caseId, CodingBenchmarkArm.A, 0,
                status, CodingBenchmarkVerdict.PENDING, 1, 2L,
                "worker", System.currentTimeMillis() + 60_000L, "", "", 1_000L, 1_000L);
    }

    private static CodingBenchmarkTrial succeeded(String trialId, String caseId) {
        return new CodingBenchmarkTrial(
                trialId, "9003", caseId, CodingBenchmarkArm.A, 0,
                CodingBenchmarkTrialStatus.SUCCEEDED, CodingBenchmarkVerdict.PASS, 1, 3L,
                "", 0L, "", "", 1_000L, 2_000L);
    }

    private void writeSnapshotWithCases(String snapshotId) throws Exception {
        Path snapshotRoot = tempDir.resolve("snapshots").resolve(snapshotId);
        Files.createDirectories(snapshotRoot);
        String digest = "sha256:" + "b".repeat(64);
        ObjectMapper objectMapper = new ObjectMapper();
        for (String manifest : REFERENCED_MANIFESTS) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotId", snapshotId);
            payload.put("snapshotDigest", digest);
            if (manifest.equals("environment-manifest.json")) {
                payload.put("images", List.of(
                        "registry.example/benchmark-agent@sha256:" + "a".repeat(64)));
            }
            if (manifest.equals("readiness-report.json")) {
                payload.put("ready", true);
            }
            if (manifest.equals("dataset-manifest.json")) {
                payload.put("caseCount", 20);
                payload.put("cases", List.of(
                        caseNode("fresh-case", "FRESH_PRIMARY", "JAVA", "MEDIUM"),
                        caseNode("public-case", "PUBLIC_ANCHOR", "JAVA", "MEDIUM")
                ));
            }
            Files.writeString(snapshotRoot.resolve(manifest), objectMapper.writeValueAsString(payload),
                    StandardCharsets.UTF_8);
        }
        Map<String, String> manifestHashes = new LinkedHashMap<>();
        for (String manifest : REFERENCED_MANIFESTS) {
            manifestHashes.put(manifest, sha256(snapshotRoot.resolve(manifest)));
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("snapshotId", snapshotId);
        provenance.put("snapshotDigest", digest);
        provenance.put("manifestSha256", manifestHashes);
        Files.writeString(snapshotRoot.resolve("benchmark-provenance.json"),
                objectMapper.writeValueAsString(provenance), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> caseNode(String caseId, String slice, String language, String difficulty) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("caseId", caseId);
        node.put("slice", slice);
        node.put("language", language);
        node.put("difficulty", difficulty);
        node.put("repositoryId", "dataset/" + caseId);
        return node;
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return "sha256:" + java.util.HexFormat.of().formatHex(digest);
    }

    /** Records dispatcher kick calls without running the dispatch loop. */
    static final class KickRecordingDispatcher extends CodingBenchmarkDispatcher {
        final List<KickCall> kicks = new ArrayList<>();

        KickRecordingDispatcher() {
            super(
                    new InMemoryEvaluationRunStore(),
                    new CodingBenchmarkDispatcherTest.RecordingTrialStore(),
                    new FakeCodingBenchmarkExecutionPort(),
                    snapshotId -> List.of(),
                    new CodingBenchmarkDispatcherTest.StubRequestAdapter(Path.of("unused")),
                    Runnable::run,
                    runId -> {
                    });
        }

        @Override
        public void kick(String campaignId, String snapshotId) {
            kicks.add(new KickCall(campaignId, snapshotId));
        }

        record KickCall(String campaignId, String snapshotId) {
        }
    }
}
