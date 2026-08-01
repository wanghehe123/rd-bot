package com.wish.rd.bootstrap.controller.admin.evaluation;

import com.wish.rd.bootstrap.evaluation.impl.CodingBenchmarkCampaignService;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkSnapshot;
import com.wish.rd.engine.evaluation.model.CodingBenchmarkTrial;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * REST API for coding benchmark campaign orchestration.
 *
 * <p>Endpoints follow the fail-closed readiness contract: probe campaigns are only accepted
 * when the target snapshot is verified ready by the {@code FileSystemCodingBenchmarkCatalog}.</p>
 */
@RestController
@RequestMapping("/admin/evaluations/coding-benchmarks")
public class CodingBenchmarkController {

    private static final Logger log = LoggerFactory.getLogger(CodingBenchmarkController.class);
    private final CodingBenchmarkCampaignService campaignService;

    public CodingBenchmarkController(CodingBenchmarkCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    /**
     * Lists all verified-ready snapshots discoverable from the evaluation catalog.
     *
     * @return snapshot metadata sorted newest-first by snapshotId
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<CodingBenchmarkSnapshot>> listSnapshots() {
        return ResponseEntity.ok(campaignService.listReadySnapshots());
    }

    /**
     * Returns one snapshot by its frozen ID, or 404 if not found or not yet verified.
     *
     * @param snapshotId the frozen snapshot identifier
     * @return snapshot metadata including case count and digest
     */
    @GetMapping("/snapshots/{snapshotId}")
    public ResponseEntity<CodingBenchmarkSnapshot> getSnapshot(@PathVariable("snapshotId") String snapshotId) {
        return campaignService.findReadySnapshot(snapshotId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lists recent coding-benchmark campaigns newest-first so the console can show history
     * without requiring the run to have been started from the current browser session.
     *
     * @param limit maximum campaigns to return (default 20)
     * @return coding-benchmark evaluation runs only
     */
    @GetMapping("/campaigns")
    public ResponseEntity<List<EvaluationRun>> listCampaigns(
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(campaignService.listCampaigns(limit));
    }

    /**
     * Returns one coding-benchmark campaign by run id.
     *
     * @param runId parent evaluation run identifier
     * @return campaign run or 404
     */
    @GetMapping("/campaigns/{runId}")
    public ResponseEntity<EvaluationRun> getCampaign(@PathVariable("runId") String runId) {
        return campaignService.findCampaign(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lists trials for a coding-benchmark campaign (case × arm board).
     *
     * @param runId parent evaluation run identifier
     * @return trials sorted by caseId, arm, replicateNo
     */
    @GetMapping("/campaigns/{runId}/trials")
    public ResponseEntity<List<CodingBenchmarkTrial>> listTrials(@PathVariable("runId") String runId) {
        return ResponseEntity.ok(campaignService.listTrials(runId));
    }

    /**
     * Returns lifecycle events and allowlisted artifact previews for one trial.
     */
    @GetMapping("/campaigns/{runId}/trials/{trialId}")
    public ResponseEntity<com.wish.rd.bootstrap.evaluation.model.CodingBenchmarkTrialDetailView> getTrialDetail(
            @PathVariable("runId") String runId,
            @PathVariable("trialId") String trialId
    ) {
        return ResponseEntity.ok(campaignService.getTrialDetail(runId, trialId));
    }

    /**
     * Returns one allowlisted trial artifact as text content.
     */
    @GetMapping("/campaigns/{runId}/trials/{trialId}/artifacts/{artifactKey}")
    public ResponseEntity<com.wish.rd.bootstrap.evaluation.model.CodingBenchmarkTrialDetailView.ArtifactContent> getTrialArtifact(
            @PathVariable("runId") String runId,
            @PathVariable("trialId") String trialId,
            @PathVariable("artifactKey") String artifactKey
    ) {
        return ResponseEntity.ok(campaignService.getTrialArtifact(runId, trialId, artifactKey));
    }

    /**
     * Launches a PROBE campaign that selects one FRESH_PRIMARY and one PUBLIC_ANCHOR case
     * from the given snapshot and schedules 8 trials (2 × 4 arms).
     *
     * <p>No prompt or retrieval configuration is tuned after probing; probes are strictly
     * for wiring smoke-testing the frozen snapshot and campaign orchestration.</p>
     *
     * @param request probe campaign request containing the target snapshotId
     * @return the created probe evaluation run
     */
    @PostMapping("/probe")
    public ResponseEntity<EvaluationRun> createProbe(@RequestBody ProbeCampaignRequest request) {
        try {
            EvaluationRun run = campaignService.createProbeCampaign(
                    request.snapshotId(),
                    request.name() == null ? "" : request.name()
            );
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
        } catch (Exception e) {
            log.error("[CODING_BENCHMARK] probe campaign failed", e);
            throw e;
        }
    }

    /**
     * Launches a cost-limited FORMAL campaign (5 cases × 4 arms = 20 trials, no sentinels).
     *
     * <p>{@code sentinelCaseIds} is accepted for API compatibility but ignored in this mode.</p>
     *
     * @param request formal campaign request containing snapshotId and name
     * @return the created formal evaluation run
     */
    @PostMapping("/formal")
    public ResponseEntity<EvaluationRun> createFormal(@RequestBody FormalCampaignRequest request) {
        EvaluationRun run = campaignService.createFormalCampaign(
                request.snapshotId(),
                request.name() == null ? "" : request.name(),
                request.sentinelCaseIds() == null ? Set.of() : request.sentinelCaseIds()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    /**
     * Pauses trial dispatch for an active coding-benchmark campaign without changing run status.
     *
     * @param runId parent evaluation run identifier
     * @return the updated run snapshot with {@code dispatchPaused=true}
     */
    @PostMapping("/campaigns/{runId}/pause")
    public ResponseEntity<EvaluationRun> pause(@PathVariable("runId") String runId) {
        try {
            return ResponseEntity.ok(campaignService.pause(runId));
        } catch (Exception e) {
            log.error("[CODING_BENCHMARK] pause campaign failed runId={}", runId, e);
            throw e;
        }
    }

    /**
     * Clears the dispatch-pause flag and re-kicks the dispatcher when trials are still running.
     *
     * @param runId parent evaluation run identifier
     * @return the updated run snapshot with {@code dispatchPaused=false}
     */
    @PostMapping("/campaigns/{runId}/resume")
    public ResponseEntity<EvaluationRun> resume(@PathVariable("runId") String runId) {
        try {
            return ResponseEntity.ok(campaignService.resume(runId));
        } catch (Exception e) {
            log.error("[CODING_BENCHMARK] resume campaign failed runId={}", runId, e);
            throw e;
        }
    }

    /**
     * Cancels an active coding-benchmark campaign and every non-terminal trial.
     *
     * @param runId parent evaluation run identifier
     * @return the cancelled run snapshot
     */
    @PostMapping("/campaigns/{runId}/cancel")
    public ResponseEntity<EvaluationRun> cancel(@PathVariable("runId") String runId) {
        try {
            return ResponseEntity.ok(campaignService.cancel(runId));
        } catch (Exception e) {
            log.error("[CODING_BENCHMARK] cancel campaign failed runId={}", runId, e);
            throw e;
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorView> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorView("SNAPSHOT_NOT_FOUND", safe(exception.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorView("INVALID_CAMPAIGN_REQUEST", safe(exception.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorView> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView("CAMPAIGN_STATE_CONFLICT", safe(exception.getMessage())));
    }

    /** Request DTO for a probe campaign. */
    public record ProbeCampaignRequest(String snapshotId, String name) {}

    /** Request DTO for a formal campaign. */
    public record FormalCampaignRequest(String snapshotId, String name, Set<String> sentinelCaseIds) {}

    /** Sanitized API error payload. */
    public record ErrorView(String code, String message) {}

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
