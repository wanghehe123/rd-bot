package com.wish.rd.engine.requirement.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.review.model.AiReviewArtifact;
import com.wish.rd.engine.requirement.review.model.AiReviewDecision;
import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.engine.requirement.review.model.AiReviewPart;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.wish.rd.framework.id.SnowflakeIdGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Orchestrates evidence packaging, model calls, strict validation, and persistent AI review decisions. */
@Service
public final class AiDeliveryReviewEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiDeliveryReviewEngine.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiReviewRunStore runStore;
    private final AiReviewPackageProvider packageProvider;
    private final AiReviewModelPort modelPort;
    private final AiReviewResultValidator validator;
    private final Supplier<String> idSupplier;
    private final LongSupplier clock;
    private final String configuredModelName;
    private final boolean enabled;

    @Autowired
    public AiDeliveryReviewEngine(
            AiReviewRunStore runStore,
            AiReviewPackageProvider packageProvider,
            ObjectProvider<AiReviewModelPort> modelPortProvider,
            SnowflakeIdGenerator idGenerator,
            @Value("${rd.ai-review.model:}") String configuredModelName,
            @Value("${rd.ai-review.enabled:false}") boolean enabled
    ) {
        this(
                runStore,
                packageProvider,
                modelPortProvider == null
                        ? AiReviewModelPort.unavailable("AI review model is not configured")
                        : modelPortProvider.getIfAvailable(
                                () -> AiReviewModelPort.unavailable("AI review model is not configured")),
                new AiReviewResultValidator(),
                (idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator)::nextIdString,
                System::currentTimeMillis,
                configuredModelName,
                enabled
        );
    }

    /**
     * Creates the AI review orchestrator.
     *
     * @param runStore persistent review state
     * @param packageProvider complete task evidence provider
     * @param modelPort external structured model
     * @param validator strict final response validator
     * @param idSupplier run/event/artifact ID supplier
     * @param clock epoch-millis clock
     * @param configuredModelName configured model name for audit before provider response
     */
    public AiDeliveryReviewEngine(
            AiReviewRunStore runStore,
            AiReviewPackageProvider packageProvider,
            AiReviewModelPort modelPort,
            AiReviewResultValidator validator,
            Supplier<String> idSupplier,
            LongSupplier clock,
            String configuredModelName
    ) {
        this(runStore, packageProvider, modelPort, validator, idSupplier, clock, configuredModelName, true);
    }

    private AiDeliveryReviewEngine(
            AiReviewRunStore runStore,
            AiReviewPackageProvider packageProvider,
            AiReviewModelPort modelPort,
            AiReviewResultValidator validator,
            Supplier<String> idSupplier,
            LongSupplier clock,
            String configuredModelName,
            boolean enabled
    ) {
        this.runStore = Objects.requireNonNull(runStore, "runStore must not be null");
        this.packageProvider = Objects.requireNonNull(packageProvider, "packageProvider must not be null");
        this.modelPort = modelPort == null ? AiReviewModelPort.unavailable("AI review model is not configured") : modelPort;
        this.validator = validator == null ? new AiReviewResultValidator() : validator;
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.configuredModelName = safe(configuredModelName);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Executes one new AI review attempt for a requirement task.
     *
     * @param task requirement task after deterministic review
     * @param deterministicReviewJson deterministic review result
     * @param trigger AUTO or USER
     * @return terminal review run
     */
    public AiReviewRun review(RdRequirementTask task, String deterministicReviewJson, String trigger) {
        if (task == null || task.taskId().isBlank()) {
            throw new IllegalArgumentException("requirement task must not be null or blank");
        }
        List<AiReviewRun> previousRuns = runStore.listByTask(task.taskId());
        int attemptNo = previousRuns.stream().mapToInt(AiReviewRun::attemptNo).max().orElse(0) + 1;
        String parentRunId = previousRuns.stream().max(java.util.Comparator.comparingInt(AiReviewRun::attemptNo))
                .map(AiReviewRun::runId).orElse("");
        AiReviewRun run = runStore.create(AiReviewRun.created(
                nextId(), task.taskId(), attemptNo, parentRunId, configuredModelName, now()));
        LOGGER.info("[AI_REVIEW] START taskId={} runId={} attempt={} trigger={}",
                task.taskId(), run.runId(), run.attemptNo(), safe(trigger));
        try {
            run = runStore.transition(run.runId(), AiReviewRunStatus.CREATED, AiReviewRunStatus.PACKAGING,
                    trigger, "package complete delivery context", "", "", now());
            AiReviewPackage reviewPackage = packageProvider.build(task, deterministicReviewJson);
            if (reviewPackage.omittedSourceCount() != 0) {
                return failRetryable(run, trigger, "PACKAGE_INCOMPLETE",
                        "AI review package omitted sources: " + reviewPackage.omittedSourceCount());
            }
            run = runStore.save(run.withPackageHash(reviewPackage.packageHash(), now()));
            appendArtifact(run.runId(), "INPUT_MANIFEST", "ai-review://input/manifest",
                    manifestPreview(reviewPackage), reviewPackage.packageHash(),
                    "{\"sourceCount\":" + reviewPackage.sources().size()
                            + ",\"partCount\":" + reviewPackage.partCount() + "}");
            LOGGER.info("[AI_REVIEW] PACKAGE taskId={} runId={} sources={} parts={} chars={} hash={}",
                    task.taskId(), run.runId(), reviewPackage.sources().size(), reviewPackage.partCount(),
                    reviewPackage.totalChars(), reviewPackage.packageHash());

            run = runStore.transition(run.runId(), AiReviewRunStatus.PACKAGING, AiReviewRunStatus.REVIEWING,
                    trigger, "invoke AI review model", "", "", now());
            String finalRawJson = invokeModel(run, reviewPackage);
            if (finalRawJson == null) {
                return runStore.find(run.runId()).orElseThrow();
            }
            run = runStore.transition(run.runId(), AiReviewRunStatus.REVIEWING, AiReviewRunStatus.VALIDATING,
                    trigger, "validate structured AI review output", "", "", now());
            AiReviewResult result = validator.validate(finalRawJson, reviewPackage.sourceIds());
            AiReviewRunStatus terminal = terminalStatus(result.decision());
            appendArtifact(run.runId(), "FINAL_REPORT", "ai-review://output/final",
                    preview(finalRawJson, 20_000), sha256(finalRawJson),
                    "{\"decision\":\"" + result.decision().name() + "\",\"score\":" + result.score() + "}");
            AiReviewRun completed = runStore.complete(run.runId(), AiReviewRunStatus.VALIDATING, terminal,
                    result, trigger, result.summary(), now());
            LOGGER.info("[AI_REVIEW] DECISION taskId={} runId={} decision={} score={} retryFromRole={}",
                    task.taskId(), run.runId(), result.decision(), result.score(),
                    result.retryFromRole() == null ? "" : result.retryFromRole().name());
            return completed;
        } catch (RuntimeException exception) {
            AiReviewRun latest = runStore.find(run.runId()).orElse(run);
            if (latest.status().isTerminal()) {
                return latest;
            }
            LOGGER.warn("[AI_REVIEW] FAILURE taskId={} runId={} category={} message={}",
                    task.taskId(), run.runId(), exception.getClass().getSimpleName(), preview(exception.getMessage(), 500));
            return failRetryable(latest, trigger, "AI_REVIEW_INVALID_OUTPUT", safe(exception.getMessage()));
        }
    }

    private String invokeModel(AiReviewRun run, AiReviewPackage reviewPackage) {
        if (reviewPackage.parts().size() == 1) {
            AiReviewPart part = reviewPackage.parts().getFirst();
            AiReviewModelPort.ModelResponse response = modelPort.review(new AiReviewModelPort.ModelRequest(
                    run.runId(), run.taskId(), "FINAL", 1, 1, part.sourceIds(), part.content()));
            return modelResponse(run, response, "FINAL", 1);
        }

        List<String> partResults = new ArrayList<>();
        for (AiReviewPart part : reviewPackage.parts()) {
            AiReviewModelPort.ModelResponse response = modelPort.review(new AiReviewModelPort.ModelRequest(
                    run.runId(), run.taskId(), "PART", part.partNo(), reviewPackage.partCount(),
                    part.sourceIds(), part.content()));
            String raw = modelResponse(run, response, "PART", part.partNo());
            if (raw == null) {
                return null;
            }
            partResults.add(raw);
        }
        String aggregation = aggregationInput(reviewPackage, partResults);
        AiReviewModelPort.ModelResponse response = modelPort.review(new AiReviewModelPort.ModelRequest(
                run.runId(), run.taskId(), "FINAL", 0, reviewPackage.partCount(),
                List.copyOf(reviewPackage.sourceIds()), aggregation));
        return modelResponse(run, response, "FINAL", 0);
    }

    private String modelResponse(
            AiReviewRun run,
            AiReviewModelPort.ModelResponse response,
            String mode,
            int partNo
    ) {
        if (response == null || !response.available()) {
            String category = response == null || response.errorCategory().isBlank()
                    ? "MODEL_UNAVAILABLE" : response.errorCategory();
            String reason = response == null ? "AI review model returned no response" : response.reason();
            failRetryable(run, "SYSTEM", category, reason);
            return null;
        }
        appendArtifact(run.runId(), "MODEL_RESPONSE", "ai-review://model/" + mode.toLowerCase() + "/" + partNo,
                preview(response.rawJson(), 20_000), sha256(response.rawJson()),
                "{\"mode\":\"" + mode + "\",\"partNo\":" + partNo
                        + ",\"model\":\"" + jsonEscape(response.modelName()) + "\"}");
        LOGGER.info("[AI_REVIEW] MODEL_CALL taskId={} runId={} mode={} part={} model={} responseHash={}",
                run.taskId(), run.runId(), mode, partNo, response.modelName(), sha256(response.rawJson()));
        return response.rawJson();
    }

    private AiReviewRun failRetryable(
            AiReviewRun run,
            String trigger,
            String errorCategory,
            String errorMessage
    ) {
        if (run.status().isTerminal()) {
            return run;
        }
        appendArtifact(run.runId(), "VALIDATION_REPORT", "ai-review://failure",
                "category=" + errorCategory + "; message=" + preview(errorMessage, 2_000),
                sha256(errorCategory + ":" + errorMessage), "{}");
        return runStore.transition(run.runId(), run.status(), AiReviewRunStatus.FAILED_RETRYABLE,
                trigger, errorMessage, errorCategory, errorMessage, now());
    }

    private void appendArtifact(
            String runId,
            String type,
            String uri,
            String contentPreview,
            String contentHash,
            String metadataJson
    ) {
        runStore.appendArtifact(new AiReviewArtifact(
                nextId(), runId, type, uri, contentPreview, contentHash, metadataJson, true, now()));
    }

    private static AiReviewRunStatus terminalStatus(AiReviewDecision decision) {
        return switch (decision) {
            case OK -> AiReviewRunStatus.SUCCEEDED_OK;
            case NOT_OK -> AiReviewRunStatus.SUCCEEDED_NOT_OK;
            case NEEDS_HUMAN -> AiReviewRunStatus.SUCCEEDED_NEEDS_HUMAN;
        };
    }

    private static String aggregationInput(AiReviewPackage reviewPackage, List<String> partResults) {
        try {
            return OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
                    "taskId", reviewPackage.taskId(),
                    "packageHash", reviewPackage.packageHash(),
                    "sourceIds", reviewPackage.sourceIds(),
                    "partResults", partResults
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize AI review aggregation input", exception);
        }
    }

    private static String manifestPreview(AiReviewPackage reviewPackage) {
        try {
            return preview(OBJECT_MAPPER.writeValueAsString(reviewPackage.sources().stream().map(source ->
                    java.util.Map.of(
                            "sourceId", source.sourceId(),
                            "sourceType", source.sourceType(),
                            "role", source.role(),
                            "artifactType", source.artifactType(),
                            "contentHash", source.contentHash(),
                            "contentLength", source.content().length()
                    )).toList()), 20_000);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize AI review manifest", exception);
        }
    }

    private String nextId() {
        String id = safe(idSupplier.get());
        if (id.isBlank()) {
            throw new IllegalStateException("AI review id supplier returned blank id");
        }
        return id;
    }

    private long now() {
        return Math.max(0L, clock.getAsLong());
    }

    private static String preview(String value, int maxChars) {
        String normalized = safe(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String jsonEscape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
