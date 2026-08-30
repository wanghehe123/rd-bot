package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.rag.project.memory.ProjectMemoryLegacyLinkStore;
import com.wish.rd.rag.project.memory.ProjectMemoryStore;
import com.wish.rd.rag.project.memory.model.LegacyExperienceInventoryDecision;
import com.wish.rd.rag.project.memory.model.ProjectMemory;
import com.wish.rd.rag.project.memory.model.ProjectMemoryLegacyLink;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevision;
import com.wish.rd.rag.project.memory.model.ProjectMemoryRevisionStatus;
import com.wish.rd.rag.project.memory.model.ProjectMemorySource;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Inventory-only legacy migration. Creates links and CANDIDATE revisions but never auto-activates
 * ambiguous or fuzzy records.
 */
public final class LegacyExperienceInventoryService {
    private static final String SCHEMA_VERSION = "legacy-inventory-v1";
    private static final String EXTRACTOR_VERSION = "legacy-inventory-v1";

    private final ProjectMemoryLegacyLinkStore linkStore;
    private final ProjectMemoryStore memoryStore;
    private final Supplier<Long> clock;

    public LegacyExperienceInventoryService(ProjectMemoryLegacyLinkStore linkStore, ProjectMemoryStore memoryStore) {
        this(linkStore, memoryStore, System::currentTimeMillis);
    }

    LegacyExperienceInventoryService(
            ProjectMemoryLegacyLinkStore linkStore,
            ProjectMemoryStore memoryStore,
            Supplier<Long> clock
    ) {
        this.linkStore = Objects.requireNonNull(linkStore, "linkStore must not be null");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore must not be null");
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public LegacyExperienceInventoryResult inventory(LegacyExperienceInventoryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Set<String> seenContentHashes = new HashSet<>();
        int eligible = 0;
        int duplicate = 0;
        int ambiguous = 0;
        int rejected = 0;
        int candidatesCreated = 0;
        List<LegacyExperienceInventoryItem> items = new ArrayList<>();

        for (WorkflowExperienceEntry entry : request.experiences()) {
            Classification classification = classify(request, entry, seenContentHashes);
            switch (classification.decision()) {
                case ELIGIBLE -> eligible++;
                case DUPLICATE -> duplicate++;
                case AMBIGUOUS -> ambiguous++;
                case REJECTED -> rejected++;
            }

            String candidateRevisionId = "";
            if (classification.decision() == LegacyExperienceInventoryDecision.ELIGIBLE) {
                candidateRevisionId = createCandidateIfAbsent(request.projectId(), entry);
                if (!candidateRevisionId.isBlank()) {
                    candidatesCreated++;
                }
            }

            ProjectMemoryLegacyLink link = new ProjectMemoryLegacyLink(
                    entry.experienceId(),
                    request.projectId(),
                    candidateRevisionId,
                    classification.decision(),
                    classification.reason()
            );
            linkStore.save(link);
            items.add(new LegacyExperienceInventoryItem(
                    entry.experienceId(), classification.decision(), classification.reason(), candidateRevisionId));
        }

        return new LegacyExperienceInventoryResult(
                request.projectId(),
                request.experiences().size(),
                eligible,
                duplicate,
                ambiguous,
                rejected,
                candidatesCreated,
                List.copyOf(items)
        );
    }

    private Classification classify(
            LegacyExperienceInventoryRequest request,
            WorkflowExperienceEntry entry,
            Set<String> seenContentHashes
    ) {
        if (entry.projectId().isBlank()) {
            return Classification.of(LegacyExperienceInventoryDecision.AMBIGUOUS, "missing project identity");
        }
        if (!entry.projectId().equals(request.projectId())) {
            return Classification.of(LegacyExperienceInventoryDecision.AMBIGUOUS, "project identity mismatch");
        }
        if (!entry.repositoryFingerprint().isBlank()
                && !request.expectedRepositoryFingerprint().isBlank()
                && !entry.repositoryFingerprint().equals(request.expectedRepositoryFingerprint())) {
            return Classification.of(LegacyExperienceInventoryDecision.AMBIGUOUS, "repository fingerprint conflict");
        }
        if (!entry.redacted()) {
            return Classification.of(LegacyExperienceInventoryDecision.AMBIGUOUS, "legacy row is not redacted");
        }
        if (entry.failure() || !entry.reusable()) {
            return Classification.of(LegacyExperienceInventoryDecision.REJECTED, "failed or incomplete delivery");
        }
        if (entry.evidenceQuality() < 0.5d) {
            return Classification.of(LegacyExperienceInventoryDecision.REJECTED, "incomplete delivery evidence");
        }
        if (entry.sourceArtifactId().isBlank()) {
            return Classification.of(LegacyExperienceInventoryDecision.AMBIGUOUS, "source artifact is not verifiable");
        }

        String contentHash = experienceContentHash(entry);
        if (linkStore.findByLegacyExperienceId(entry.experienceId()).isPresent()) {
            return Classification.of(LegacyExperienceInventoryDecision.DUPLICATE, "legacy row already inventoried");
        }
        if (!seenContentHashes.add(contentHash)) {
            return Classification.of(LegacyExperienceInventoryDecision.DUPLICATE, "duplicate content hash in batch");
        }
        if (memoryStore.findByLogicalKey(
                request.projectId(), entry.role().name(), mapType(entry), logicalKey(entry)).isPresent()) {
            return Classification.of(LegacyExperienceInventoryDecision.DUPLICATE, "logical identity already exists");
        }

        return Classification.of(LegacyExperienceInventoryDecision.ELIGIBLE, "eligible for candidate creation");
    }

    private String createCandidateIfAbsent(String projectId, WorkflowExperienceEntry entry) {
        var existingLink = linkStore.findByLegacyExperienceId(entry.experienceId());
        if (existingLink.isPresent() && !existingLink.get().revisionId().isBlank()) {
            return existingLink.get().revisionId();
        }

        ProjectMemoryType memoryType = mapType(entry);
        String scopeRole = entry.role().name();
        String logicalKeyValue = logicalKey(entry);
        String memoryId = "legacy-memory-" + entry.experienceId();
        String revisionId = "legacy-revision-" + entry.experienceId();

        ProjectMemory memory = memoryStore.findByLogicalKey(projectId, scopeRole, memoryType, logicalKeyValue)
                .orElseGet(() -> memoryStore.create(new ProjectMemory(
                        memoryId, projectId, scopeRole, memoryType, logicalKeyValue, 1L)));

        if (memoryStore.listRevisions(memory.memoryId()).stream()
                .anyMatch(revision -> revision.revisionId().equals(revisionId))) {
            return revisionId;
        }

        String contentHash = experienceContentHash(entry);
        ProjectMemoryRevision revision = new ProjectMemoryRevision(
                revisionId,
                memory.memoryId(),
                1L,
                ProjectMemoryRevisionStatus.CANDIDATE,
                entry.title(),
                entry.summary(),
                entry.contentJson(),
                contentHash,
                SCHEMA_VERSION,
                "",
                clock.get(),
                1L
        );
        memoryStore.addRevision(revision);
        memoryStore.addSource(new ProjectMemorySource(
                "legacy-source-" + entry.experienceId(),
                revisionId,
                projectId,
                entry.taskId(),
                entry.stageRunId(),
                entry.sourceArtifactId(),
                "legacy://experience/" + entry.experienceId(),
                contentHash,
                entry.sourceRevision(),
                EXTRACTOR_VERSION,
                SCHEMA_VERSION,
                entry.summary()
        ));
        return revisionId;
    }

    static String experienceContentHash(WorkflowExperienceEntry entry) {
        String canonical = "sourceArtifactId=" + entry.sourceArtifactId()
                + "\ncontentJson=" + entry.contentJson()
                + "\nsummary=" + entry.summary();
        return digest(canonical);
    }

    private static ProjectMemoryType mapType(WorkflowExperienceEntry entry) {
        return switch (entry.experienceType()) {
            case CODE_CHANGE, DELIVERY_REPORT -> ProjectMemoryType.PROCEDURAL;
            case QA_REPORT -> ProjectMemoryType.EPISODIC;
            default -> ProjectMemoryType.SEMANTIC;
        };
    }

    private static String logicalKey(WorkflowExperienceEntry entry) {
        return "legacy:" + entry.experienceType().name().toLowerCase(Locale.ROOT) + ":" + entry.experienceId();
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Classification(LegacyExperienceInventoryDecision decision, String reason) {
        static Classification of(LegacyExperienceInventoryDecision decision, String reason) {
            return new Classification(decision, reason);
        }
    }
}
