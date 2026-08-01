package com.wish.rd.bootstrap.evaluation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.evaluation.EvaluationProperties;
import com.wish.rd.engine.evaluation.CodingBenchmarkCaseRuntime;
import com.wish.rd.engine.evaluation.CodingBenchmarkRuntimeCatalogPort;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end local rehearsal of Step 6 control-plane behaviour using
 * {@link FakeCodingBenchmarkExecutionPort} — no Docker, Redis, or Spring context.
 */
class CodingBenchmarkStep6RehearsalTest {

    private static final String SNAPSHOT_ID = "probe-snap";
    private static final List<String> REFERENCED_MANIFESTS = List.of(
            "dataset-manifest.json",
            "environment-manifest.json",
            "knowledge-manifest.json",
            "analysis-plan.json",
            "readiness-report.json",
            "runtime-manifest.json"
    );
    private static final List<String> PROBE_CASE_IDS = List.of("fresh-case", "public-case");

    @TempDir
    Path tempDir;

    private InMemoryEvaluationRunStore runStore;
    private CodingBenchmarkDispatcherTest.RecordingTrialStore trialStore;
    private FakeCodingBenchmarkExecutionPort fakePort;
    private CodingBenchmarkDispatcher dispatcher;
    private CodingBenchmarkCampaignService service;
    private CodingBenchmarkCampaignRecovery recovery;
    private ExecutorService workerPool;
    private Path prepRoot;

    @BeforeEach
    void setUp() throws Exception {
        runStore = new InMemoryEvaluationRunStore();
        trialStore = new CodingBenchmarkDispatcherTest.RecordingTrialStore();
        fakePort = new FakeCodingBenchmarkExecutionPort(40L);
        workerPool = Executors.newCachedThreadPool();
        prepRoot = Files.createDirectories(tempDir.resolve("prep"));

        EvaluationProperties properties = new EvaluationProperties();
        properties.setRepositoryRoot(tempDir);
        properties.setCodingBenchmarkRoot(tempDir.resolve("snapshots"));
        properties.setCodingBenchmarkPrepRoot(prepRoot);
        FileSystemCodingBenchmarkCatalog catalog =
                new FileSystemCodingBenchmarkCatalog(properties, new ObjectMapper());
        writeSnapshotWithCases(SNAPSHOT_ID);

        CodingBenchmarkRuntimeCatalogPort runtimeCatalog = snapshotId -> PROBE_CASE_IDS.stream()
                .map(this::runtimeForCase)
                .toList();

        CodingBenchmarkDispatcherTest.StubRequestAdapter adapter =
                new CodingBenchmarkDispatcherTest.StubRequestAdapter(tempDir.resolve("workspaces"));

        CodingBenchmarkCampaignService[] serviceHolder = new CodingBenchmarkCampaignService[1];
        dispatcher = new CodingBenchmarkDispatcher(
                runStore, trialStore, fakePort, runtimeCatalog, adapter,
                workerPool::execute,
                runId -> {
                    if (serviceHolder[0] != null) {
                        serviceHolder[0].onTrialsDrained(runId);
                    }
                });
        service = new CodingBenchmarkCampaignService(
                catalog, trialStore, runStore, properties, dispatcher, fakePort, new SnowflakeIdGenerator());
        serviceHolder[0] = service;
        recovery = new CodingBenchmarkCampaignRecovery(runStore, dispatcher);
    }

    @AfterEach
    void tearDown() {
        workerPool.shutdownNow();
    }

    @Test
    void localRehearsalPauseResumeCancelAndRecovery() throws Exception {
    // 1–2. Fake executor + createProbe → 8 trials
    EvaluationRun probe = service.createProbeCampaign(SNAPSHOT_ID, "rehearsal-probe");
    assertEquals(EvaluationRunStatus.RUNNING_TRIALS, probe.status());
    assertEquals(8, trialStore.listByCampaign(probe.runId()).size());

    // 3. pause — no new claims while paused (in-flight may drain)
    awaitPartialProgress(probe.runId());
    int claimsAtPause = trialStore.claimCallCount();
    int queuedAtPause = countQueued(probe.runId());
    EvaluationRun paused = service.pause(probe.runId());
    assertTrue(paused.dispatchPaused());
    Thread.sleep(300L);
    assertEquals(claimsAtPause, trialStore.claimCallCount(), "pause must block new claims");
    assertEquals(queuedAtPause, countQueued(probe.runId()), "queued trials must not be claimed while paused");

    // 4. resume — remaining trials complete to SUCCEEDED run
    service.resume(probe.runId());
    awaitRunStatus(probe.runId(), EvaluationRunStatus.SUCCEEDED, 30);
    assertEquals(8, countByStatus(probe.runId(), CodingBenchmarkTrialStatus.SUCCEEDED));

    // 5. second campaign — cancel mid-flight
    fakePort.clearCampaign(probe.runId());
    EvaluationRun cancelRun = service.createProbeCampaign(SNAPSHOT_ID, "rehearsal-cancel");
    awaitPartialProgress(cancelRun.runId());
    EvaluationRun cancelled = service.cancel(cancelRun.runId());
    assertEquals(EvaluationRunStatus.CANCELLED, cancelled.status());
        for (CodingBenchmarkTrial trial : trialStore.listByCampaign(cancelRun.runId())) {
      if (trial.status() == CodingBenchmarkTrialStatus.SUCCEEDED) {
          continue;
      }
      assertEquals(CodingBenchmarkTrialStatus.CANCELLED, trial.status(),
              "non-terminal trial " + trial.trialId() + " must be CANCELLED");
    }
    assertTrue(fakePort.isCampaignCancelled(cancelRun.runId()));

    // 6. recovery — leftover QUEUED trials finish without re-running SUCCEEDED
    fakePort.clearCampaign(cancelRun.runId());
    String recoveryRunId = "recovery-campaign";
    List<CodingBenchmarkTrial> recoveryTrials = new ArrayList<>();
    recoveryTrials.add(succeededTrial("rec-done-1", recoveryRunId, "fresh-case"));
    recoveryTrials.add(succeededTrial("rec-done-2", recoveryRunId, "public-case"));
        for (int index = 0; index < 4; index++) {
      String caseId = PROBE_CASE_IDS.get(index % PROBE_CASE_IDS.size());
      recoveryTrials.add(queuedTrial("rec-q-" + index, recoveryRunId, caseId, CodingBenchmarkArm.A));
    }
    trialStore.createAll(recoveryRunId, recoveryTrials);
    Map<String, Long> succeededVersionsBefore = Map.of(
            "rec-done-1", trialStore.find("rec-done-1").orElseThrow().version(),
            "rec-done-2", trialStore.find("rec-done-2").orElseThrow().version());
    seedRunningCampaign(recoveryRunId);

    recovery.recover();
    awaitRunStatus(recoveryRunId, EvaluationRunStatus.SUCCEEDED, 30);
    assertEquals(6, countByStatus(recoveryRunId, CodingBenchmarkTrialStatus.SUCCEEDED));
    for (Map.Entry<String, Long> entry : succeededVersionsBefore.entrySet()) {
      assertEquals(entry.getValue(), trialStore.find(entry.getKey()).orElseThrow().version(),
              "recovery must not re-execute already-SUCCEEDED trial " + entry.getKey());
    }
  }

    private void awaitPartialProgress(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            int queued = countQueued(runId);
            int active = countActive(runId);
            if (queued > 0 && (active > 0 || trialStore.claimCallCount() > 0)) {
                return;
            }
            Thread.sleep(25L);
        }
        fail("campaign " + runId + " did not reach partial progress within timeout");
    }

    private void awaitRunStatus(String runId, EvaluationRunStatus status, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L;
        while (System.currentTimeMillis() < deadline) {
            EvaluationRunStatus current = runStore.find(runId)
                    .map(EvaluationRun::status)
                    .orElse(null);
            if (current == status) {
                return;
            }
            Thread.sleep(25L);
        }
        EvaluationRunStatus actual = runStore.find(runId).map(EvaluationRun::status).orElse(null);
        fail("run " + runId + " expected " + status + " but was " + actual);
    }

    private int countQueued(String runId) {
        return (int) trialStore.listByCampaign(runId).stream()
                .filter(t -> t.status() == CodingBenchmarkTrialStatus.QUEUED)
                .count();
    }

    private int countActive(String runId) {
        return (int) trialStore.listByCampaign(runId).stream()
                .filter(t -> t.status().isActive())
                .count();
    }

    private int countByStatus(String runId, CodingBenchmarkTrialStatus status) {
        return (int) trialStore.listByCampaign(runId).stream()
                .filter(t -> t.status() == status)
                .count();
    }

    private void seedRunningCampaign(String runId) {
        long now = System.currentTimeMillis();
        EvaluationRunConfig config = new EvaluationRunConfig(
                "recovery", SNAPSHOT_ID + ".json", null, SNAPSHOT_ID, 0, "", "", 3600,
                null, 0, false, "", "", EvaluationMode.CODING_BENCHMARK, SNAPSHOT_ID);
        EvaluationRun run = EvaluationRun.created(runId, config, 1, "", now)
                .withStatus(EvaluationRunStatus.RUNNING_TRIALS, "running trials", "", "", now);
        runStore.create(run);
    }

    private CodingBenchmarkCaseRuntime runtimeForCase(String caseId) {
        try {
            Path caseRoot = Files.createDirectories(prepRoot.resolve(caseId));
            Path repo = Files.createDirectories(caseRoot.resolve("repo"));
            Path cache = Files.createDirectories(caseRoot.resolve("cache"));
            Path verifierCache = Files.createDirectories(caseRoot.resolve("verifier-cache"));
            return CodingBenchmarkCaseRuntime.of(
                    prepRoot,
                    caseId,
                    "registry.example/agent@sha256:" + "a".repeat(64),
                    "registry.example/oracle@sha256:" + "b".repeat(64),
                    repo,
                    cache,
                    repo,
                    verifierCache,
                    null,
                    null,
                    "tests/runtime",
                    List.of("/opt/rd/agent"),
                    List.of("/opt/rd/oracle"),
                    60_000L,
                    8_000L
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static CodingBenchmarkTrial queuedTrial(
            String trialId,
            String campaignId,
            String caseId,
            CodingBenchmarkArm arm
    ) {
        return CodingBenchmarkTrial.queued(trialId, campaignId, caseId, arm, 0, 1_000L);
    }

    private static CodingBenchmarkTrial succeededTrial(String trialId, String campaignId, String caseId) {
        return new CodingBenchmarkTrial(
                trialId, campaignId, caseId, CodingBenchmarkArm.A, 0,
                CodingBenchmarkTrialStatus.SUCCEEDED, CodingBenchmarkVerdict.PASS, 1, 5L,
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
}
