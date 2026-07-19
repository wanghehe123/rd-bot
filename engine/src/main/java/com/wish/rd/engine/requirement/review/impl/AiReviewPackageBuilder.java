package com.wish.rd.engine.requirement.review.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.review.model.AiReviewPackage;
import com.wish.rd.engine.requirement.review.model.AiReviewPart;
import com.wish.rd.engine.requirement.review.model.AiReviewSource;
import com.wish.rd.engine.requirement.review.AiReviewPackageProvider;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds a complete task-scoped AI review manifest and lossless bounded input parts. */
@Component
public final class AiReviewPackageBuilder implements AiReviewPackageProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TaskMaterialStore materialStore;
    private final RoleContextPackageStore contextStore;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore stageArtifactStore;
    private final RetrievalRunStore retrievalRunStore;
    private final int maxInputChars;

    /**
     * Creates the evidence package builder.
     *
     * @param materialStore task material store
     * @param contextStore role context store
     * @param stageRunStore role attempt store
     * @param stageArtifactStore role artifact store
     * @param retrievalRunStore retrieval attempt and artifact store
     * @param maxInputChars maximum characters per model-input part
     */
    public AiReviewPackageBuilder(
            TaskMaterialStore materialStore,
            RoleContextPackageStore contextStore,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore stageArtifactStore,
            RetrievalRunStore retrievalRunStore,
            @Value("${rd.ai-review.max-input-chars:80000}") int maxInputChars
    ) {
        this.materialStore = java.util.Objects.requireNonNull(materialStore, "materialStore must not be null");
        this.contextStore = java.util.Objects.requireNonNull(contextStore, "contextStore must not be null");
        this.stageRunStore = java.util.Objects.requireNonNull(stageRunStore, "stageRunStore must not be null");
        this.stageArtifactStore = java.util.Objects.requireNonNull(stageArtifactStore, "stageArtifactStore must not be null");
        this.retrievalRunStore = java.util.Objects.requireNonNull(retrievalRunStore, "retrievalRunStore must not be null");
        this.maxInputChars = Math.max(256, maxInputChars);
    }

    /**
     * Builds a complete package from trusted task-scoped stores.
     *
     * @param task requirement task under review
     * @param deterministicReviewJson deterministic delivery-review result
     * @return immutable review package with zero omitted sources
     */
    @Override
    public AiReviewPackage build(RdRequirementTask task, String deterministicReviewJson) {
        if (task == null || task.taskId().isBlank()) {
            throw new IllegalArgumentException("requirement task must not be null or blank");
        }
        String taskId = task.taskId();
        List<AiReviewSource> sources = new ArrayList<>();
        sources.add(source("task:" + taskId, "TASK", "", "TASK_SNAPSHOT",
                json(task), "{}", task.updateTimeEpochMillis()));

        materialStore.listByTask(taskId).stream()
                .sorted(Comparator.comparing(TaskMaterial::materialId))
                .forEach(material -> sources.add(source(
                        "material:" + material.materialId(), "MATERIAL", "", material.materialType().name(),
                        json(material), material.metadataJson(), material.updateTimeEpochMillis())));

        contextStore.listByTask(taskId).stream()
                .sorted(Comparator.comparing(RoleContextPackage::role)
                        .thenComparingInt(RoleContextPackage::packageVersion)
                        .thenComparing(RoleContextPackage::packageId))
                .forEach(context -> sources.add(source(
                        "role-context:" + context.packageId(), "ROLE_CONTEXT", context.role(), "CONTEXT_PACKAGE",
                        json(context), "{}", context.createdAtEpochMillis())));

        stageRunStore.listByTask(taskId).stream()
                .sorted(Comparator.comparing((AgentStageRun run) -> run.role().ordinal())
                        .thenComparingInt(AgentStageRun::attemptNo))
                .forEach(run -> sources.add(source(
                        "stage-run:" + run.stageRunId(), "STAGE_RUN", run.role().name(), "STAGE_SNAPSHOT",
                        json(run), "{}", run.updateTimeEpochMillis())));

        stageArtifactStore.listByTask(taskId).stream()
                .sorted(Comparator.comparing(AgentStageArtifact::createdAtEpochMillis)
                        .thenComparing(AgentStageArtifact::artifactId))
                .forEach(artifact -> sources.add(source(
                        "stage-artifact:" + artifact.artifactId(), "STAGE_ARTIFACT", artifact.role().name(),
                        artifact.artifactType(), artifactContent(artifact), artifact.metadataJson(),
                        artifact.createdAtEpochMillis())));

        for (RetrievalRun run : retrievalRunStore.listByTask(taskId).stream()
                .sorted(Comparator.comparingInt(RetrievalRun::attemptNo)
                        .thenComparing(RetrievalRun::createdAtEpochMillis)
                        .thenComparing(RetrievalRun::runId)).toList()) {
            sources.add(source("retrieval-run:" + run.runId(), "RETRIEVAL_RUN", run.role(), "RUN_SNAPSHOT",
                    json(run), "{}", run.updatedAtEpochMillis()));
            retrievalRunStore.listArtifacts(run.runId()).stream()
                    .sorted(Comparator.comparing(RetrievalRunArtifact::createdAtEpochMillis)
                            .thenComparing(RetrievalRunArtifact::artifactId))
                    .forEach(artifact -> sources.add(source(
                            "retrieval-artifact:" + artifact.artifactId(), "RETRIEVAL_ARTIFACT", run.role(),
                            artifact.artifactType(), retrievalArtifactContent(artifact), "{}",
                            artifact.createdAtEpochMillis())));
        }

        String deterministicReview = deterministicReviewJson == null || deterministicReviewJson.isBlank()
                ? "{}" : deterministicReviewJson.strip();
        sources.add(source("delivery-review:" + taskId, "DETERMINISTIC_REVIEW", "", "DELIVERY_REVIEW",
                deterministicReview, "{}", task.updateTimeEpochMillis()));

        ensureUniqueSourceIds(sources);
        List<AiReviewSource> immutableSources = List.copyOf(sources);
        List<AiReviewPart> parts = partition(immutableSources);
        int totalChars = immutableSources.stream().mapToInt(source -> source.content().length()).sum();
        return new AiReviewPackage(taskId, immutableSources, parts, totalChars, 0, packageHash(immutableSources));
    }

    private List<AiReviewPart> partition(List<AiReviewSource> sources) {
        List<AiReviewPart> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        LinkedHashSet<String> currentSourceIds = new LinkedHashSet<>();
        for (AiReviewSource source : sources) {
            String block = sourceBlock(source);
            int offset = 0;
            while (offset < block.length()) {
                if (current.length() == maxInputChars) {
                    flushPart(parts, current, currentSourceIds);
                }
                int remaining = maxInputChars - current.length();
                int take = Math.min(remaining, block.length() - offset);
                current.append(block, offset, offset + take);
                currentSourceIds.add(source.sourceId());
                offset += take;
                if (current.length() == maxInputChars) {
                    flushPart(parts, current, currentSourceIds);
                }
            }
        }
        flushPart(parts, current, currentSourceIds);
        return List.copyOf(parts);
    }

    private static void flushPart(
            List<AiReviewPart> parts,
            StringBuilder current,
            Set<String> currentSourceIds
    ) {
        if (current.isEmpty()) {
            return;
        }
        String content = current.toString();
        parts.add(new AiReviewPart(parts.size() + 1, List.copyOf(currentSourceIds), content, content.length()));
        current.setLength(0);
        currentSourceIds.clear();
    }

    private static String sourceBlock(AiReviewSource source) {
        return "\n=== SOURCE " + source.sourceId() + " type=" + source.sourceType()
                + " role=" + source.role() + " artifact=" + source.artifactType()
                + " hash=" + source.contentHash() + " ===\n" + source.content() + "\n";
    }

    private static AiReviewSource source(
            String sourceId,
            String sourceType,
            String role,
            String artifactType,
            String content,
            String metadataJson,
            long createdAt
    ) {
        String redacted = redact(content);
        return new AiReviewSource(sourceId, sourceType, role, artifactType, sha256(redacted), redacted,
                redact(metadataJson), createdAt);
    }

    private static String artifactContent(AgentStageArtifact artifact) {
        return "uri=" + artifact.artifactUri() + "\nsummary=" + artifact.summary()
                + "\ncontent=" + artifact.contentPreview() + "\nsourceHash=" + artifact.contentHash();
    }

    private static String retrievalArtifactContent(RetrievalRunArtifact artifact) {
        return "uri=" + artifact.artifactUri() + "\ncontent=" + artifact.contentPreview()
                + "\nsourceHash=" + artifact.contentHash() + "\nredacted=" + artifact.redacted();
    }

    private static void ensureUniqueSourceIds(List<AiReviewSource> sources) {
        Set<String> ids = new java.util.HashSet<>();
        for (AiReviewSource source : sources) {
            if (!ids.add(source.sourceId())) {
                throw new IllegalStateException("duplicate AI review source id: " + source.sourceId());
            }
        }
    }

    private static String packageHash(List<AiReviewSource> sources) {
        StringBuilder value = new StringBuilder();
        sources.forEach(source -> value.append(source.sourceId()).append(':')
                .append(source.contentHash()).append('\n'));
        return sha256(value.toString());
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize AI review source", exception);
        }
    }

    private static String redact(String value) {
        String normalized = value == null ? "" : value;
        return normalized
                .replaceAll("(?i)(authorization\\s*[=:]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>")
                .replaceAll("(?i)(token|api[_-]?key|password|secret|cookie)\\s*[=:]\\s*([^\\s,;\\\"]+)",
                        "$1=<redacted>")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
