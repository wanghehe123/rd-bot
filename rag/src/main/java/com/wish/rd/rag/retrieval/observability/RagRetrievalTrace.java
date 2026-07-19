package com.wish.rd.rag.retrieval.observability;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.retrieval.model.ChannelSearchOutcome;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Produces bounded, redacted console diagnostics and control-plane previews for a retrieval run.
 *
 * <p>All output uses the {@code [RAG_TRACE]} marker so an operator can filter the backend console
 * without logging raw prompts, complete documents, or credentials.
 */
public final class RagRetrievalTrace {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagRetrievalTrace.class);
    private static final int CONSOLE_PREVIEW_LIMIT = 1_000;
    private static final int ARTIFACT_PREVIEW_LIMIT = 2_000;
    private static final int EVIDENCE_PREVIEW_LIMIT = 1_200;
    private static final int MAX_CONSOLE_EVIDENCE = 80;
    private static final int MAX_CHANNEL_CANDIDATES = 80;

    private RagRetrievalTrace() {
    }

    /**
     * Writes the entry point and its approved scope to the backend console.
     *
     * @param run active retrieval attempt
     */
    public static void started(RetrievalRun run) {
        if (run == null) {
            return;
        }
        LOGGER.info("[RAG_TRACE] START runId={} taskId={} consumer={} role={} attempt={} scope={} query={}",
                run.runId(), run.taskId(), run.consumerType(), run.role(), run.attemptNo(),
                run.knowledgeBaseIds(), preview(run.queryPreview(), CONSOLE_PREVIEW_LIMIT));
    }

    /**
     * Writes the retrieval planning decision to the backend console.
     *
     * @param runId retrieval attempt identifier
     * @param planPreview redacted planning summary
     */
    public static void planned(String runId, String planPreview) {
        LOGGER.info("[RAG_TRACE] PLAN runId={} {}", safe(runId), preview(planPreview, CONSOLE_PREVIEW_LIMIT));
    }

    /**
     * Writes one fan-out channel result to the backend console.
     *
     * @param runId retrieval attempt identifier
     * @param outcome one channel outcome
     */
    public static void channel(String runId, ChannelSearchOutcome outcome) {
        if (outcome == null) {
            return;
        }
        LOGGER.info("[RAG_TRACE] CHANNEL runId={} channel={} status={} chunks={} durationMs={} errorCategory={} error={}",
                safe(runId), outcome.channelName(), outcome.failed() ? "FAILED" : "SUCCEEDED",
                outcome.chunkCount(), outcome.durationMillis(), outcome.errorCategory(),
                preview(outcome.errorMessage(), CONSOLE_PREVIEW_LIMIT));
        List<RetrievedChunk> chunks = outcome.result().chunks();
        int limit = Math.min(chunks.size(), MAX_CHANNEL_CANDIDATES);
        for (int index = 0; index < limit; index++) {
            RetrievedChunk chunk = chunks.get(index);
            LOGGER.info("[RAG_TRACE] CANDIDATE runId={} channel={} rank={} chunkId={} knowledgeBase={} source={} score={} preview={}",
                    safe(runId), safe(outcome.channelName()), index + 1, safe(chunk.chunkId()),
                    safe(chunk.knowledgeBaseId()), safe(chunk.sourceName()), chunk.score(),
                    preview(chunk.content(), CONSOLE_PREVIEW_LIMIT));
        }
        if (chunks.size() > limit) {
            LOGGER.info("[RAG_TRACE] CANDIDATE runId={} channel={} omittedCount={} reason=console-preview-budget",
                    safe(runId), safe(outcome.channelName()), chunks.size() - limit);
        }
    }

    /**
     * Writes every selected evidence preview that will reach the context package.
     *
     * @param runId retrieval attempt identifier
     * @param chunks fused evidence in final ranking order
     */
    public static void selectedEvidence(String runId, List<RetrievedChunk> chunks) {
        List<RetrievedChunk> safeChunks = chunks == null ? List.of() : chunks;
        int limit = Math.min(safeChunks.size(), MAX_CONSOLE_EVIDENCE);
        for (int index = 0; index < limit; index++) {
            RetrievedChunk chunk = safeChunks.get(index);
            LOGGER.info("[RAG_TRACE] EVIDENCE runId={} rank={} chunkId={} knowledgeBase={} source={} type={} score={} preview={}",
                    safe(runId), index + 1, safe(chunk.chunkId()), safe(chunk.knowledgeBaseId()), safe(chunk.sourceName()),
                    safe(chunk.knowledgeType()), chunk.score(), preview(chunk.content(), CONSOLE_PREVIEW_LIMIT));
        }
        if (safeChunks.size() > limit) {
            LOGGER.info("[RAG_TRACE] EVIDENCE runId={} omittedCount={} reason=console-preview-budget",
                    safe(runId), safeChunks.size() - limit);
        }
    }

    /**
     * Writes the final quality decision to the backend console.
     *
     * @param runId retrieval attempt identifier
     * @param outcome terminal or quality outcome
     * @param candidateCount candidate evidence count before final packing
     * @param selectedCount final evidence count
     * @param reason outcome explanation
     */
    public static void outcome(String runId, String outcome, int candidateCount, int selectedCount, String reason) {
        LOGGER.info("[RAG_TRACE] OUTCOME runId={} outcome={} candidates={} selected={} reason={}",
                safe(runId), safe(outcome), Math.max(0, candidateCount), Math.max(0, selectedCount),
                preview(reason, CONSOLE_PREVIEW_LIMIT));
    }

    /**
     * Writes one persisted process or content observation to the console using the same run marker.
     *
     * @param runId retrieval attempt identifier
     * @param artifactType process or evidence type
     * @param artifactUri logical source location
     * @param contentPreview bounded redacted content
     */
    public static void artifact(String runId, String artifactType, String artifactUri, String contentPreview) {
        LOGGER.info("[RAG_TRACE] ARTIFACT runId={} type={} uri={} content={}", safe(runId), safe(artifactType),
                safe(artifactUri), preview(contentPreview, CONSOLE_PREVIEW_LIMIT));
    }

    /**
     * Writes a redacted retrieval failure to the backend console.
     *
     * @param runId retrieval attempt identifier
     * @param errorCategory failure class
     * @param errorMessage failure message
     */
    public static void failure(String runId, String errorCategory, String errorMessage) {
        LOGGER.warn("[RAG_TRACE] FAILURE runId={} category={} message={}", safe(runId), safe(errorCategory),
                preview(errorMessage, CONSOLE_PREVIEW_LIMIT));
    }

    /**
     * Creates a redacted, single-line preview that is safe to place in an artifact or log record.
     *
     * @param value raw content
     * @param limit maximum returned character count
     * @return compact redacted preview
     */
    public static String preview(String value, int limit) {
        String compact = safe(value).replaceAll("\\s+", " ");
        String redacted = compact
                .replaceAll("(?i)(authorization\\s*[=:]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>")
                .replaceAll("(?i)(token|api[_-]?key|password|secret|authorization)=([^\\s,;]+)", "$1=<redacted>")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>");
        int safeLimit = Math.max(1, limit);
        return redacted.length() <= safeLimit ? redacted : redacted.substring(0, safeLimit) + "...";
    }

    /**
     * Computes a stable content hash for redacted artifact correlation without retaining full text.
     *
     * @param value source content
     * @return SHA-256 hash with an explicit algorithm prefix
     */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    /**
     * Builds a bounded artifact preview for one fan-out channel.
     *
     * @param outcome one channel result
     * @return redacted channel summary
     */
    public static String channelArtifactPreview(ChannelSearchOutcome outcome) {
        if (outcome == null) {
            return "channel=unknown; status=UNAVAILABLE";
        }
        return preview("channel=" + outcome.channelName()
                + "; status=" + (outcome.failed() ? "FAILED" : "SUCCEEDED")
                + "; chunks=" + outcome.chunkCount()
                + "; durationMs=" + outcome.durationMillis()
                + "; errorCategory=" + outcome.errorCategory()
                + "; error=" + outcome.errorMessage()
                + candidatePreview(outcome.result().chunks()), ARTIFACT_PREVIEW_LIMIT);
    }

    /**
     * Builds a bounded artifact preview for the ranked evidence passed to the context package.
     *
     * @param chunks fused evidence in rank order
     * @return redacted evidence summary
     */
    public static String evidenceArtifactPreview(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> safeChunks = chunks == null ? List.of() : chunks;
        StringBuilder preview = new StringBuilder();
        for (int index = 0; index < safeChunks.size() && index < 3; index++) {
            RetrievedChunk chunk = safeChunks.get(index);
            if (preview.length() > 0) {
                preview.append('\n');
            }
            preview.append('#').append(index + 1)
                    .append(" chunkId=").append(safe(chunk.chunkId()))
                    .append("; knowledgeBase=").append(safe(chunk.knowledgeBaseId()))
                    .append("; source=").append(safe(chunk.sourceName()))
                    .append("; score=").append(chunk.score())
                    .append("; content=").append(preview(chunk.content(), EVIDENCE_PREVIEW_LIMIT));
        }
        if (safeChunks.size() > 3) {
            preview.append("\nomittedCount=").append(safeChunks.size() - 3)
                    .append("; reason=artifact-preview-budget");
        }
        return preview(preview.toString(), ARTIFACT_PREVIEW_LIMIT);
    }

    /**
     * Builds one independently inspectable candidate projection.
     *
     * @param channelName originating channel
     * @param rank channel-local rank
     * @param chunk candidate evidence
     * @return redacted candidate content and provenance
     */
    public static String candidateArtifactPreview(String channelName, int rank, RetrievedChunk chunk) {
        if (chunk == null) {
            return "channel=" + safe(channelName) + "; rank=" + Math.max(1, rank) + "; content=unavailable";
        }
        return preview("channel=" + safe(channelName)
                + "; rank=" + Math.max(1, rank)
                + "; chunkId=" + safe(chunk.chunkId())
                + "; knowledgeBase=" + safe(chunk.knowledgeBaseId())
                + "; source=" + safe(chunk.sourceName())
                + "; type=" + safe(chunk.knowledgeType())
                + "; score=" + chunk.score()
                + "; content=" + safe(chunk.content()), ARTIFACT_PREVIEW_LIMIT);
    }

    /**
     * Builds one independently inspectable final evidence projection.
     *
     * @param rank final fused rank
     * @param chunk selected evidence
     * @return redacted selected content and provenance
     */
    public static String selectedEvidenceArtifactPreview(int rank, RetrievedChunk chunk) {
        return candidateArtifactPreview("FUSED", rank, chunk);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String candidatePreview(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> safeChunks = chunks == null ? List.of() : chunks;
        StringBuilder preview = new StringBuilder();
        for (int index = 0; index < safeChunks.size() && index < MAX_CHANNEL_CANDIDATES; index++) {
            RetrievedChunk chunk = safeChunks.get(index);
            preview.append("; candidate#").append(index + 1)
                    .append(" chunkId=").append(safe(chunk.chunkId()))
                    .append("; knowledgeBase=").append(safe(chunk.knowledgeBaseId()))
                    .append("; score=").append(chunk.score())
                    .append("; content=").append(preview(chunk.content(), EVIDENCE_PREVIEW_LIMIT));
        }
        if (safeChunks.size() > MAX_CHANNEL_CANDIDATES) {
            preview.append("; omittedCount=").append(safeChunks.size() - MAX_CHANNEL_CANDIDATES)
                    .append("; reason=artifact-preview-budget");
        }
        return preview.toString();
    }
}
