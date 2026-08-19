package com.wish.rd.bootstrap.rag.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.engine.retrieval.model.ChannelAudit;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.retrieval.MultiChannelRetrievalEngine;
import com.wish.rd.rag.retrieval.impl.IntentDirectedVectorSearchChannel;
import com.wish.rd.rag.retrieval.impl.KeywordBM25SearchChannel;
import com.wish.rd.rag.retrieval.model.ChannelSearchOutcome;
import com.wish.rd.rag.retrieval.model.RetrievalExecutionResult;
import com.wish.rd.rag.retrieval.model.RetrievalRequest;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.vector.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Uses the project binding as a hard boundary for requirement knowledge retrieval.
 *
 * <p>依赖 {@link RdProjectService}，而后者只在 {@code rd.knowledge.store=postgres} 下注册，
 * 因此这里必须用同一个属性条件。类扫描组件上的 {@code @ConditionalOnBean} 会先登记 bean
 * definition 再求值，使得
 * {@code RetrievalRunConfiguration#knowledgeRetrievalModeRouter} 的
 * {@code @ConditionalOnBean} 误判为满足，直到实例化才发现依赖缺失。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class ProjectScopedRequirementKnowledgeSearchAdapter implements RequirementKnowledgeSearchPort {

    private final VectorStore vectorStore;
    private final RdProjectService projectService;
    private final WorkflowExperienceStore experienceStore;
    private final RagStreamTaskRegistry taskRegistry;
    private final boolean experienceChannelEnabled;

    public ProjectScopedRequirementKnowledgeSearchAdapter(
            VectorStore vectorStore,
            RdProjectService projectService
    ) {
        this(vectorStore, projectService, WorkflowExperienceStore.noop(), null, false);
    }

    @Autowired
    public ProjectScopedRequirementKnowledgeSearchAdapter(
            VectorStore vectorStore,
            RdProjectService projectService,
            WorkflowExperienceStore experienceStore,
            RagStreamTaskRegistry taskRegistry
    ) {
        this(vectorStore, projectService, experienceStore, taskRegistry, true);
    }

    private ProjectScopedRequirementKnowledgeSearchAdapter(
            VectorStore vectorStore,
            RdProjectService projectService,
            WorkflowExperienceStore experienceStore,
            RagStreamTaskRegistry taskRegistry,
            boolean experienceChannelEnabled
    ) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.projectService = Objects.requireNonNull(projectService, "projectService must not be null");
        this.experienceStore = experienceStore == null ? WorkflowExperienceStore.noop() : experienceStore;
        this.taskRegistry = taskRegistry;
        this.experienceChannelEnabled = experienceChannelEnabled && taskRegistry != null;
    }

    @Override
    public RetrievalScope resolveScope(RdRequirementTask task) {
        if (task == null) {
            return new RetrievalScope(List.of(), "", true, "task is missing");
        }
        if (task.projectId().isBlank()) {
            return new RetrievalScope(
                    List.of(), repositoryFingerprint(task), false,
                    "task has no project binding; repository metadata only"
            );
        }
        try {
            RdProject project = projectService.getEnabled(task.projectId());
            List<String> knowledgeBaseIds = project.knowledgeBaseId().isBlank()
                    ? List.of() : List.of(project.knowledgeBaseId());
            return new RetrievalScope(
                    knowledgeBaseIds, repositoryFingerprint(project), true,
                    knowledgeBaseIds.isEmpty() ? "project has no enabled knowledge-base binding" : ""
            );
        } catch (RuntimeException exception) {
            return new RetrievalScope(
                    List.of(), repositoryFingerprint(task), true,
                    exception.getClass().getSimpleName() + ": " + safe(exception.getMessage())
            );
        }
    }

    @Override
    public SearchResult search(
            RdRequirementTask task,
            AgentRole role,
            String query,
            RetrievalScope scope,
            int topK
    ) {
        RetrievalScope safeScope = scope == null
                ? resolveScope(task) : scope;
        // Empty scope must never be interpreted by VectorStore as an all-knowledge-base search.
        if (safeScope.knowledgeBaseIds().isEmpty()) {
            return new SearchResult(
                    List.of(),
                    List.of(new ChannelAudit(
                            "ProjectScope", true, 0, "MISSING_SCOPE", safeScope.missingReason()
                    ))
            );
        }
        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(List.of(
                new IntentDirectedVectorSearchChannel(vectorStore),
                new KeywordBM25SearchChannel(vectorStore)
        ));
        RetrievalExecutionResult result = engine.retrieveDetailed(new RetrievalRequest(
                query, Optional.empty(), safeScope.knowledgeBaseIds(), Math.max(1, topK)
        ));
        List<RetrievedChunk> chunks = result.bundle().chunks();
        List<RoleContextEvidence> knowledgeEvidence = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> evidence(chunks.get(index), index))
                .toList();
        List<ChannelAudit> audits = new ArrayList<>(result.channelOutcomes().values().stream()
                .map(this::audit)
                .toList());
        List<RoleContextEvidence> experienceEvidence = experienceEvidence(task, role, query, Math.max(1, topK));
        if (experienceChannelEnabled) {
            audits.add(new ChannelAudit(
                    "WorkflowExperience", false, experienceEvidence.size(), "", ""
            ));
        }
        int experienceLimit = Math.max(0, (int) Math.floor(Math.max(1, topK) * 0.30d));
        List<RoleContextEvidence> boundedExperiences = experienceEvidence.stream()
                .limit(experienceLimit)
                .toList();
        int knowledgeLimit = Math.max(0, Math.max(1, topK) - boundedExperiences.size());
        List<RoleContextEvidence> evidence = new ArrayList<>(knowledgeEvidence.stream()
                .limit(knowledgeLimit)
                .toList());
        evidence.addAll(boundedExperiences);
        return new SearchResult(List.copyOf(evidence), List.copyOf(audits));
    }

    private List<RoleContextEvidence> experienceEvidence(
            RdRequirementTask task,
            AgentRole role,
            String query,
            int topK
    ) {
        if (!experienceChannelEnabled || task == null || role == null || topK < 4) {
            return List.of();
        }
        LinkedHashSet<String> sourceTasks = new LinkedHashSet<>();
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        List<RoleContextEvidence> result = new ArrayList<>();
        List<WorkflowExperienceEntry> candidates = experienceStore.searchReusableScoped(
                query,
                task.taskId(),
                task.projectId(),
                repositoryFingerprint(task),
                role,
                Math.max(100, topK * 10)
        );
        int rank = 0;
        for (WorkflowExperienceEntry entry : candidates == null
                ? List.<WorkflowExperienceEntry>of() : candidates) {
            if (entry == null
                    || !entry.reusable()
                    || entry.failure()
                    || !entry.redacted()
                    || !roleCompatible(role, entry.experienceType())
                    || !sameProject(task, entry)) {
                continue;
            }
            String content = firstNonBlank(entry.summary(), entry.title(), entry.contentJson());
            String hash = sha256(entry.contentJson() + "\n" + content);
            if (!sourceTasks.add(entry.taskId()) || !hashes.add(hash)) {
                continue;
            }
            rank++;
            result.add(new RoleContextEvidence(
                    entry.experienceId(), "WORKFLOW_EXPERIENCE",
                    "rd-experience://" + entry.experienceId(), entry.title(), hash,
                    bounded(content, 2_000), entry.createdAtEpochMillis(),
                    "same-project role-compatible experience hint from task=" + entry.taskId(),
                    0.25d / rank, "EXPERIENCE_HINT", false
            ));
        }
        return List.copyOf(result);
    }

    private boolean sameProject(RdRequirementTask task, WorkflowExperienceEntry entry) {
        if (entry == null) {
            return false;
        }
        if (!entry.projectId().isBlank()) {
            return !task.projectId().isBlank() && task.projectId().equals(entry.projectId());
        }
        if (!entry.repositoryFingerprint().isBlank()) {
            return entry.repositoryFingerprint().equals(repositoryFingerprint(task));
        }
        String sourceTaskId = entry.taskId();
        if (taskRegistry == null || sourceTaskId == null || sourceTaskId.isBlank()) {
            return false;
        }
        try {
            RdRequirementTask source = taskRegistry.getRequirementTask(sourceTaskId);
            if (!task.projectId().isBlank()) {
                return task.projectId().equals(source.projectId());
            }
            String targetRepository = repositoryFingerprint(task);
            return !targetRepository.isBlank() && targetRepository.equals(repositoryFingerprint(source));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean roleCompatible(AgentRole role, WorkflowExperienceType type) {
        if (type == null) {
            return false;
        }
        return switch (role) {
            case REQUIREMENT_REVIEWER -> type == WorkflowExperienceType.REQUIREMENT_REVIEW
                    || type == WorkflowExperienceType.DELIVERY_REPORT;
            case SOLUTION_ARCHITECT -> type == WorkflowExperienceType.TECHNICAL_DESIGN
                    || type == WorkflowExperienceType.REQUIREMENT_REVIEW;
            case CODING_AGENT -> type == WorkflowExperienceType.CODE_CHANGE
                    || type == WorkflowExperienceType.TECHNICAL_DESIGN;
            case QA_AGENT -> type == WorkflowExperienceType.QA_REPORT
                    || type == WorkflowExperienceType.DELIVERY_REPORT;
            default -> false;
        };
    }

    private RoleContextEvidence evidence(RetrievedChunk chunk, int rank) {
        String sourceUri = sourceUri(chunk);
        String type = requiredEvidenceType(chunk);
        return new RoleContextEvidence(
                chunk.chunkId(), chunk.knowledgeType(), sourceUri,
                firstNonBlank(chunk.sourceName(), chunk.chunkId()), sha256(chunk.content()),
                bounded(chunk.content(), 2_000), System.currentTimeMillis(),
                "project-scoped RRF rank=" + (rank + 1),
                1.0d / (rank + 1.0d), type, false
        );
    }

    private ChannelAudit audit(ChannelSearchOutcome outcome) {
        return new ChannelAudit(
                outcome.channelName(), outcome.failed(), outcome.chunkCount(),
                outcome.errorCategory(), bounded(outcome.errorMessage(), 400)
        );
    }

    private String requiredEvidenceType(RetrievedChunk chunk) {
        String corpus = (chunk.knowledgeType() + " " + chunk.sourceName() + " "
                + String.join(" ", chunk.metadata().values())).toLowerCase(Locale.ROOT);
        if (containsAny(corpus, "test", "qa", "spec", "playwright", "junit", "验收", "测试")) {
            return "TEST_ENTRY";
        }
        if (containsAny(corpus, "controller", "service", "java", "code", "source", "代码", "源码")) {
            return "CODE_SYMBOL";
        }
        if (containsAny(corpus, "api", "interface", "contract", "接口", "契约")) {
            return "API_CONTRACT";
        }
        if (containsAny(corpus, "architecture", "design", "架构", "方案")) {
            return "ARCHITECTURE";
        }
        if (containsAny(corpus, "database", "schema", "sql", "数据库", "表结构")) {
            return "DATABASE_SCHEMA";
        }
        if (containsAny(corpus, "frontend", "page", "ui", "页面", "前端")) {
            return "PAGE_BEHAVIOR";
        }
        return "DOMAIN_KNOWLEDGE";
    }

    private String sourceUri(RetrievedChunk chunk) {
        Map<String, String> metadata = chunk.metadata();
        String explicit = firstNonBlank(
                metadata.get("sourceUri"), metadata.get("artifactUri"), metadata.get("path")
        );
        if (!explicit.isBlank()) {
            return explicit.contains("#") ? explicit : explicit + "#" + chunk.chunkId();
        }
        String document = firstNonBlank(metadata.get("documentId"), chunk.sourceName());
        return "knowledge://" + chunk.knowledgeBaseId() + "/" + document + "#" + chunk.chunkId();
    }

    private boolean containsAny(String corpus, String... needles) {
        for (String needle : needles) {
            if (corpus.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String repositoryFingerprint(RdProject project) {
        if (!project.repoOwner().isBlank() && !project.repoName().isBlank()) {
            return (project.repoOwner() + "/" + project.repoName()).toLowerCase(Locale.ROOT);
        }
        return safe(project.repositoryUrl()).toLowerCase(Locale.ROOT);
    }

    private String repositoryFingerprint(RdRequirementTask task) {
        if (!task.repoOwner().isBlank() && !task.repoName().isBlank()) {
            return (task.repoOwner() + "/" + task.repoName()).toLowerCase(Locale.ROOT);
        }
        return safe(task.repositoryUrl()).toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : digest) {
                output.append(String.format(Locale.ROOT, "%02x", item));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String bounded(String value, int maxChars) {
        String safeValue = safe(value);
        return safeValue.length() <= maxChars ? safeValue : safeValue.substring(0, maxChars);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
