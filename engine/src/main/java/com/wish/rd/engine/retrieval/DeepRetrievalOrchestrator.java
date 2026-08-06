package com.wish.rd.engine.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.iterative.IterativeRetrievalLoop;
import com.wish.rd.engine.retrieval.iterative.RetrievalIterationLimits;
import com.wish.rd.engine.retrieval.iterative.RetrievalIterationState;
import com.wish.rd.engine.retrieval.iterative.RetrievalStopReason;
import com.wish.rd.engine.retrieval.model.ChannelAudit;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.engine.retrieval.model.RetrievalScope;
import com.wish.rd.engine.retrieval.model.SearchResult;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.observability.RagRetrievalTrace;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Runs one project-scoped requirement retrieval attempt and applies deterministic role evidence gates.
 * Raw upstream role output is used only to refine the query. A structurally valid handoff manifest addressed to the
 * current role can satisfy the corresponding role gate; the RustFS object remains content-verified before execution.
 */
public final class DeepRetrievalOrchestrator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> ARCHITECT_TYPES = Set.of(
            "ARCHITECTURE", "INTERFACE", "API_CONTRACT", "DATABASE_SCHEMA"
    );
    private static final Set<String> CODING_TYPES = Set.of(
            "CODE_SYMBOL", "SOURCE_CODE", "API_CONTRACT", "DATABASE_SCHEMA"
    );
    private static final Set<String> QA_TYPES = Set.of(
            "TEST_ENTRY", "TEST_CASE", "ACCEPTANCE_TARGET", "API_CONTRACT", "PAGE_BEHAVIOR"
    );

    private final RetrievalRunLifecycle lifecycle;
    private final RequirementKnowledgeSearchPort searchPort;

    public DeepRetrievalOrchestrator(
            RetrievalRunLifecycle lifecycle,
            RequirementKnowledgeSearchPort searchPort
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.searchPort = searchPort == null ? RequirementKnowledgeSearchPort.noop() : searchPort;
    }

    public RetrievalOutcome retrieve(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role,
            String stageRunId,
            String upstreamClues,
            int topK
    ) {
        return retrieveOnce(task, materials, consumerType, role, stageRunId, upstreamClues, topK);
    }

    /**
     * Multi-round retrieval bounded by {@link RetrievalIterationLimits} and
     * {@link com.wish.rd.engine.retrieval.iterative.IterativeRetrievalStopGate}.
     * Each round runs a full single-shot retrieval; missing evidence types refine the next query clues.
     * Default callers remain on {@link #retrieve} (single shot).
     */
    public RetrievalOutcome retrieveIterative(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role,
            String stageRunId,
            String upstreamClues,
            int topK,
            RetrievalIterationLimits limits
    ) {
        RetrievalIterationLimits bound = limits == null ? RetrievalIterationLimits.defaults() : limits;
        AtomicReference<String> clues = new AtomicReference<>(safe(upstreamClues));
        AtomicReference<RetrievalOutcome> last = new AtomicReference<>();
        long startedAt = System.currentTimeMillis();
        RetrievalStopReason stop = new IterativeRetrievalLoop().run(bound, startedAt, before -> {
            RetrievalOutcome outcome = retrieveOnce(
                    task, materials, consumerType, role, stageRunId, clues.get(), topK
            );
            last.set(outcome);
            Set<String> selectedIds = outcome.selectedEvidence().stream()
                    .map(RoleContextEvidence::evidenceId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean gateSatisfied = outcome.succeeded();
            // DeepRetrieval NEED_INPUT means rewrite/search again, not hard user clarification.
            boolean needsClarification = false;
            long tokens = Math.max(1L, selectedIds.size() * 32L);
            RetrievalIterationState assessed = before.withAssess(
                    gateSatisfied,
                    needsClarification,
                    selectedIds,
                    tokens,
                    Math.max(0L, System.currentTimeMillis() - startedAt)
            );
            if (!gateSatisfied && !outcome.missingEvidenceTypes().isEmpty()) {
                clues.set(refineClues(clues.get(), outcome.missingEvidenceTypes()));
            }
            return assessed;
        });
        RetrievalOutcome outcome = last.get();
        if (outcome == null) {
            throw new IllegalStateException("iterative retrieval produced no outcome");
        }
        return withStopReason(outcome, stop.name());
    }

    private RetrievalOutcome retrieveOnce(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role,
            String stageRunId,
            String upstreamClues,
            int topK
    ) {
        Objects.requireNonNull(task, "task must not be null");
        RetrievalConsumerType consumer = consumerType == null
                ? RetrievalConsumerType.REQUIREMENT_BASE : consumerType;
        if (consumer == RetrievalConsumerType.AGENT_ROLE && role == null) {
            throw new IllegalArgumentException("role is required for AGENT_ROLE retrieval");
        }
        List<TaskMaterial> safeMaterials = materials == null ? List.of() : List.copyOf(materials);
        RetrievalScope scope = safeScope(task);
        String query = query(task, role, upstreamClues);
        RetrievalRun run = lifecycle.start(
                task.taskId(), consumer, role == null ? "" : role.name(), safe(stageRunId), query,
                scope.knowledgeBaseIds()
        );
        RagRetrievalTrace.started(run);

        append(run.runId(), "QUERY", "rag://retrieval/query", query, sha256(query));
        String plan = plan(consumer, role, scope, topK);
        append(run.runId(), "RETRIEVAL_PLAN", "rag://retrieval/plan", plan, sha256(plan));
        RagRetrievalTrace.planned(run.runId(), plan);

        SearchResult searchResult;
        try {
            searchResult = searchPort.search(task, role, query, scope, Math.max(1, topK));
            if (searchResult == null) {
                searchResult = SearchResult.empty();
            }
        } catch (RuntimeException exception) {
            String error = bounded(exception.getMessage(), 600);
            append(run.runId(), "QUALITY_REPORT", "rag://retrieval/quality",
                    "decision=FAILED_RETRYABLE; reason=knowledge search failed; error=" + error,
                    sha256(error));
            RetrievalRun failed = lifecycle.failRetryable(
                    run.runId(), exception.getClass().getSimpleName(), error
            );
            RagRetrievalTrace.outcome(run.runId(), failed.status().name(), 0, 0, error);
            return new RetrievalOutcome(
                    run.runId(), failed.status(), consumer, role, safe(stageRunId), List.of(),
                    failed.qualityDecision(), "", List.of("SEARCH_CHANNEL"), List.of(), failed.stopReason()
            );
        }

        appendChannelAudits(run.runId(), searchResult.channels());
        appendMaterialEvidence(run.runId(), safeMaterials);
        List<RoleContextEvidence> roots = rootEvidence(task, safeMaterials, scope);
        List<RoleContextEvidence> handoffCandidates = directedHandoffEvidence(
                role, upstreamClues, Math.max(task.createTimeEpochMillis(), task.updateTimeEpochMillis())
        );
        List<RoleContextEvidence> candidates = mergeCandidates(roots, searchResult.candidates(), handoffCandidates);
        List<RoleContextEvidence> nonRootCandidates = new ArrayList<>(
                searchResult.candidates() == null ? List.of() : searchResult.candidates());
        nonRootCandidates.addAll(handoffCandidates);
        Selection selection = select(role, consumer, roots, nonRootCandidates, Math.max(1, topK));
        List<String> missing = missingEvidence(task, consumer, role, scope, selection.selected());
        boolean repositoryDiscoveryFallback = allowsRepositoryDiscoveryFallback(consumer, role, missing);
        List<RoleContextEvidence> selectedEvidence = new ArrayList<>(selection.selected());
        if (repositoryDiscoveryFallback) {
            selectedEvidence.add(repositoryDiscoveryEvidence(task, role, missing));
        }
        selectedEvidence = deduplicate(selectedEvidence);

        for (RoleContextEvidence evidence : selectedEvidence) {
            append(run.runId(), "SELECTED_EVIDENCE", evidence.sourceUri(), evidencePreview(evidence),
                    evidence.contentHash().isBlank() ? sha256(evidence.summary()) : evidence.contentHash());
        }

        EvidenceQualityDecision decision = missing.isEmpty()
                ? EvidenceQualityDecision.SUFFICIENT
                : repositoryDiscoveryFallback
                ? EvidenceQualityDecision.DEGRADED_ACCEPTABLE
                : EvidenceQualityDecision.NEED_INPUT;
        String reason = missing.isEmpty()
                ? "角色关键证据门禁通过"
                : repositoryDiscoveryFallback
                ? "缺少角色关键证据: " + String.join(",", missing) + "；已降级为受限仓库发现"
                : "缺少角色关键证据: " + String.join(",", missing);
        Selection finalSelection = new Selection(selectedEvidence, selection.omittedIds());
        String quality = qualityReport(decision, scope, candidates.size(), finalSelection, missing, reason);
        RetrievalRunArtifact qualityArtifact = append(
                run.runId(), "QUALITY_REPORT", "rag://retrieval/quality", quality, sha256(quality)
        );

        RetrievalRun terminal = missing.isEmpty() || repositoryDiscoveryFallback
                ? lifecycle.complete(
                        run.runId(), candidates.size(), selectedEvidence.size(), repositoryDiscoveryFallback, reason)
                : lifecycle.waitForInput(run.runId(), candidates.size(), selectedEvidence.size(), reason);
        append(run.runId(), "RETRIEVAL_OUTCOME", "rag://retrieval/outcome",
                "outcome=" + terminal.status().name()
                        + "; candidates=" + candidates.size()
                        + "; selected=" + selectedEvidence.size()
                        + "; reason=" + reason,
                sha256(terminal.status().name() + reason));
        RagRetrievalTrace.outcome(
                run.runId(), terminal.status().name(), candidates.size(), selectedEvidence.size(), reason
        );
        return new RetrievalOutcome(
                run.runId(), terminal.status(), consumer, role, safe(stageRunId), selectedEvidence,
                terminal.qualityDecision(), qualityArtifact.artifactId(), missing, selection.omittedIds(), reason
        );
    }

    private RetrievalScope safeScope(RdRequirementTask task) {
        RetrievalScope resolved = searchPort.resolveScope(task);
        return resolved == null
                ? new RetrievalScope(List.of(), repositoryFingerprint(task), !task.projectId().isBlank(),
                "scope resolver returned no project boundary")
                : resolved;
    }

    private List<RoleContextEvidence> rootEvidence(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalScope scope
    ) {
        List<RoleContextEvidence> roots = new ArrayList<>();
        long collectedAt = Math.max(task.createTimeEpochMillis(), task.updateTimeEpochMillis());
        addRoot(roots, "task-" + task.taskId(), "TASK_INPUT", "rd-task://" + task.taskId(),
                task.title(), task.title() + "\n" + task.expectedResult(), collectedAt, "REQUIREMENT_ROOT");
        if (!scope.repositoryFingerprint().isBlank()) {
            String repository = scope.repositoryFingerprint()
                    + (task.baseBranch().isBlank() ? "" : "@" + task.baseBranch());
            addRoot(roots, "repository-" + task.taskId(), "REPOSITORY", "repo://" + repository,
                    "Repository scope", repository, collectedAt, "REPOSITORY_SCOPE");
        }
        if (!task.acceptanceCriteriaJson().equals("[]")) {
            addRoot(roots, "acceptance-" + task.taskId(), "ACCEPTANCE_CRITERIA",
                    "rd-task://" + task.taskId() + "/acceptance", "Acceptance criteria",
                    task.acceptanceCriteriaJson(), collectedAt, "ACCEPTANCE_CRITERIA");
        }
        for (TaskMaterial material : materials) {
            if (material == null || material.sourceUri().startsWith("rd-experience://")) {
                continue;
            }
            String summary = bounded(material.contentPreview(), 2_000);
            if (summary.isBlank()) {
                continue;
            }
            String uri = firstNonBlank(material.sourceUri(), material.artifactUri(),
                    "rd-task://" + task.taskId() + "/material/" + material.materialId());
            roots.add(new RoleContextEvidence(
                    firstNonBlank(material.materialId(), sha256(uri)), material.materialType().name(), uri,
                    firstNonBlank(material.title(), material.materialType().name()),
                    firstNonBlank(material.contentHash(), sha256(summary)), summary,
                    Math.max(material.createTimeEpochMillis(), material.updateTimeEpochMillis()),
                    "task material root evidence", 1.0d, "REQUIREMENT_MATERIAL", true
            ));
        }
        return deduplicate(roots);
    }

    private void addRoot(
            List<RoleContextEvidence> roots,
            String id,
            String sourceType,
            String uri,
            String title,
            String summary,
            long collectedAt,
            String requiredType
    ) {
        if (safe(summary).isBlank()) {
            return;
        }
        roots.add(new RoleContextEvidence(
                id, sourceType, uri, title, sha256(summary), bounded(summary, 2_000), collectedAt,
                "immutable task root evidence", 1.0d, requiredType, true
        ));
    }

    private Selection select(
            AgentRole role,
            RetrievalConsumerType consumer,
            List<RoleContextEvidence> roots,
            List<RoleContextEvidence> searchCandidates,
            int topK
    ) {
        List<RoleContextEvidence> selected = new ArrayList<>(roots);
        List<String> omitted = new ArrayList<>();
        for (RoleContextEvidence candidate : deduplicate(searchCandidates)) {
            if (!valid(candidate) || !roleRelevant(role, consumer, candidate)) {
                omitted.add(candidate == null ? "" : candidate.evidenceId());
                continue;
            }
            if (selected.size() >= topK) {
                omitted.add(candidate.evidenceId());
                continue;
            }
            selected.add(candidate);
        }
        return new Selection(deduplicate(selected), omitted.stream().filter(value -> !value.isBlank()).toList());
    }

    private boolean roleRelevant(
            AgentRole role,
            RetrievalConsumerType consumer,
            RoleContextEvidence evidence
    ) {
        if (evidence.relevanceScore() <= 0.0d) {
            return false;
        }
        if (consumer == RetrievalConsumerType.REQUIREMENT_BASE || role == AgentRole.REQUIREMENT_REVIEWER) {
            return true;
        }
        String type = evidence.requiredEvidenceType();
        if (type.equals("EXPERIENCE_HINT")) {
            return true;
        }
        return switch (role) {
            case SOLUTION_ARCHITECT -> ARCHITECT_TYPES.contains(type);
            case CODING_AGENT -> CODING_TYPES.contains(type);
            case QA_AGENT -> QA_TYPES.contains(type);
            default -> true;
        };
    }

    private List<String> missingEvidence(
            RdRequirementTask task,
            RetrievalConsumerType consumer,
            AgentRole role,
            RetrievalScope scope,
            List<RoleContextEvidence> selected
    ) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (scope.projectScopeRequired() && scope.knowledgeBaseIds().isEmpty()) {
            missing.add("PROJECT_KNOWLEDGE_SCOPE");
        }
        if (selected.stream().noneMatch(item -> item.requiredEvidenceType().equals("REQUIREMENT_ROOT"))) {
            missing.add("REQUIREMENT_ROOT");
        }
        if (!task.repositoryUrl().isBlank()
                && selected.stream().noneMatch(item -> item.requiredEvidenceType().equals("REPOSITORY_SCOPE"))) {
            missing.add("REPOSITORY_SCOPE");
        }
        if (!task.acceptanceCriteriaJson().equals("[]")
                && selected.stream().noneMatch(item -> item.requiredEvidenceType().equals("ACCEPTANCE_CRITERIA"))) {
            missing.add("ACCEPTANCE_CRITERIA");
        }
        if ((consumer == RetrievalConsumerType.REQUIREMENT_BASE || role == AgentRole.REQUIREMENT_REVIEWER)
                && selected.stream().noneMatch(item -> item.requiredEvidenceType().equals("REQUIREMENT_MATERIAL"))) {
            missing.add("REQUIREMENT_MATERIAL");
        }
        if (consumer == RetrievalConsumerType.AGENT_ROLE && role != null) {
            boolean hasRoleEvidence = selected.stream().filter(item -> !item.sharedRoot()).anyMatch(item -> switch (role) {
                case SOLUTION_ARCHITECT -> ARCHITECT_TYPES.contains(item.requiredEvidenceType());
                case CODING_AGENT -> CODING_TYPES.contains(item.requiredEvidenceType());
                case QA_AGENT -> QA_TYPES.contains(item.requiredEvidenceType());
                case REQUIREMENT_REVIEWER -> true;
                default -> true;
            });
            if (!hasRoleEvidence && role == AgentRole.SOLUTION_ARCHITECT) {
                missing.add("ARCHITECTURE_OR_INTERFACE");
            } else if (!hasRoleEvidence && role == AgentRole.CODING_AGENT) {
                missing.add("CODE_SYMBOL");
            } else if (!hasRoleEvidence && role == AgentRole.QA_AGENT) {
                missing.add("TEST_ENTRY");
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Missing semantic role evidence is recoverable inside an already constrained repository checkout.
     * Scope, task-root, acceptance, and retrieval-channel failures are not recoverable by this fallback.
     */
    private boolean allowsRepositoryDiscoveryFallback(
            RetrievalConsumerType consumer,
            AgentRole role,
            List<String> missingEvidence
    ) {
        if (consumer != RetrievalConsumerType.AGENT_ROLE || role == null || missingEvidence == null
                || missingEvidence.isEmpty()) {
            return false;
        }
        String recoverableType = switch (role) {
            case SOLUTION_ARCHITECT -> "ARCHITECTURE_OR_INTERFACE";
            case CODING_AGENT -> "CODE_SYMBOL";
            case QA_AGENT -> "TEST_ENTRY";
            default -> "";
        };
        return !recoverableType.isBlank()
                && missingEvidence.stream().allMatch(recoverableType::equals);
    }

    private RoleContextEvidence repositoryDiscoveryEvidence(
            RdRequirementTask task,
            AgentRole role,
            List<String> missingEvidence
    ) {
        String repository = firstNonBlank(repositoryFingerprint(task), "workspace");
        String summary = "未检索到 " + String.join(",", missingEvidence)
                + "；在当前工作区先进行受限仓库发现：最多 12 条只读命令、检查最多 20 个文件、读取最多 64 KiB。"
                + "仅使用 rg/find/sed/git grep 等普通仓库工具，记录定位到的文件、符号和测试入口；"
                + "不要访问基准参考答案、隐藏检查或仓库外路径。";
        return new RoleContextEvidence(
                "repository-discovery-" + safe(task.taskId()) + "-" + role.name().toLowerCase(Locale.ROOT),
                "REPOSITORY_DISCOVERY",
                "repo://" + repository + "/discovery",
                role.name() + " 受限仓库发现",
                sha256(summary),
                summary,
                Math.max(task.createTimeEpochMillis(), task.updateTimeEpochMillis()),
                "role-specific RAG evidence was unavailable; bounded repository discovery is required",
                1.0d,
                "REPOSITORY_DISCOVERY",
                false
        );
    }

    private void appendChannelAudits(String runId, List<ChannelAudit> channels) {
        for (ChannelAudit channel : channels == null ? List.<ChannelAudit>of() : channels) {
            String preview = "channel=" + channel.channel()
                    + "; failed=" + channel.failed()
                    + "; candidates=" + channel.candidateCount()
                    + "; errorCategory=" + channel.errorCategory()
                    + "; error=" + bounded(channel.errorMessage(), 400);
            append(runId, "SEARCH_CHANNEL", "rag://retrieval/channel/" + channel.channel(),
                    preview, sha256(preview));
        }
    }

    private void appendMaterialEvidence(String runId, List<TaskMaterial> materials) {
        for (TaskMaterial material : materials) {
            if (material == null || material.contentPreview().isBlank()) {
                continue;
            }
            String preview = "materialId=" + material.materialId()
                    + "; type=" + material.materialType().name()
                    + "; sourceType=" + material.sourceType().name()
                    + "; title=" + material.title()
                    + "; source=" + firstNonBlank(material.sourceUri(), material.artifactUri())
                    + "; content=" + bounded(material.contentPreview(), 1_500);
            append(runId, "MATERIAL_EVIDENCE",
                    "rag://retrieval/material/" + material.materialId(), preview,
                    firstNonBlank(material.contentHash(), sha256(material.contentPreview())));
        }
    }

    private RetrievalRunArtifact append(
            String runId,
            String type,
            String uri,
            String content,
            String hash
    ) {
        String preview = RagRetrievalTrace.preview(content, 2_000);
        RetrievalRunArtifact artifact = lifecycle.appendArtifact(
                runId, type, uri, preview, firstNonBlank(hash, sha256(content))
        );
        RagRetrievalTrace.artifact(runId, type, uri, preview);
        return artifact;
    }

    private String plan(
            RetrievalConsumerType consumer,
            AgentRole role,
            RetrievalScope scope,
            int topK
    ) {
        return "strategy=PROJECT_SCOPED_DEEP_RETRIEVAL"
                + "; consumer=" + consumer.name()
                + "; role=" + (role == null ? "" : role.name())
                + "; knowledgeBaseIds=" + String.join(",", scope.knowledgeBaseIds())
                + "; repository=" + scope.repositoryFingerprint()
                + "; topK=" + Math.max(1, topK)
                + "; rawUpstreamOutputIsQueryOnly=true"
                + "; directedHandoffManifestEligible=true";
    }

    private String qualityReport(
            EvidenceQualityDecision decision,
            RetrievalScope scope,
            int candidateCount,
            Selection selection,
            List<String> missing,
            String reason
    ) {
        return "decision=" + decision.name()
                + "; projectScopeRequired=" + scope.projectScopeRequired()
                + "; knowledgeBaseIds=" + String.join(",", scope.knowledgeBaseIds())
                + "; candidateCount=" + candidateCount
                + "; selectedCount=" + selection.selected().size()
                + "; missingEvidence=" + String.join(",", missing)
                + "; omittedCount=" + selection.omittedIds().size()
                + "; reason=" + reason;
    }

    private String evidencePreview(RoleContextEvidence evidence) {
        return "evidenceId=" + evidence.evidenceId()
                + "; sourceType=" + evidence.sourceType()
                + "; requiredEvidenceType=" + evidence.requiredEvidenceType()
                + "; sharedRoot=" + evidence.sharedRoot()
                + "; relevanceScore=" + String.format(Locale.ROOT, "%.4f", evidence.relevanceScore())
                + "; selectionReason=" + evidence.selectionReason()
                + "; title=" + evidence.title()
                + "; summary=" + bounded(evidence.summary(), 1_400);
    }

    private String query(RdRequirementTask task, AgentRole role, String upstreamClues) {
        return ((role == null ? "REQUIREMENT_BASE" : role.name())
                + "\n任务: " + task.title()
                + "\n预期结果: " + task.expectedResult()
                + "\n验收标准: " + task.acceptanceCriteriaJson()
                + "\n仓库: " + repositoryFingerprint(task)
                + (safe(upstreamClues).isBlank() ? "" : "\n上游线索(仅用于检索): " + bounded(upstreamClues, 2_000)))
                .strip();
    }

    private String repositoryFingerprint(RdRequirementTask task) {
        if (!task.repoOwner().isBlank() && !task.repoName().isBlank()) {
            return (task.repoOwner() + "/" + task.repoName()).toLowerCase(Locale.ROOT);
        }
        return safe(task.repositoryUrl()).toLowerCase(Locale.ROOT);
    }

    private List<RoleContextEvidence> directedHandoffEvidence(
            AgentRole role,
            String upstreamClues,
            long collectedAtEpochMillis
    ) {
        if (role == null || safe(upstreamClues).isBlank()) {
            return List.of();
        }
        List<RoleContextEvidence> evidence = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(upstreamClues);
            if (root == null || !root.isObject()) {
                return List.of();
            }
            JsonNode stages = root.path("stages");
            if (stages.isArray()) {
                for (JsonNode stage : stages) {
                    if (stage != null && stage.isObject()) {
                        addDirectedHandoffEvidence(
                                evidence,
                                role,
                                stage.has("handoff") ? stage.path("handoff") : stage.path("roleHandoff"),
                                stage.path("role").asText(""),
                                collectedAtEpochMillis
                        );
                    }
                }
            }
            if (root.has("roleHandoff")) {
                addDirectedHandoffEvidence(
                        evidence, role, root.path("roleHandoff"), root.path("sourceRole").asText(""),
                        collectedAtEpochMillis
                );
            }
        } catch (Exception ignored) {
            // The raw upstream output remains query-only when it is not a valid compact handoff manifest.
        }
        return deduplicate(evidence);
    }

    private void addDirectedHandoffEvidence(
            List<RoleContextEvidence> evidence,
            AgentRole role,
            JsonNode handoff,
            String fallbackSourceRole,
            long collectedAtEpochMillis
    ) {
        if (handoff == null || !handoff.isObject()
                || !role.name().equalsIgnoreCase(safe(handoff.path("targetRole").asText("")))) {
            return;
        }
        String sourceRole = firstNonBlank(handoff.path("sourceRole").asText(""), fallbackSourceRole);
        String artifactName = safe(handoff.path("artifactName").asText(""));
        String artifactUri = safe(handoff.path("artifactUri").asText(""));
        String digest = normalizeSha256(handoff.path("sha256").asText(""));
        long bytes = handoff.path("bytes").asLong(-1L);
        if (sourceRole.isBlank()
                || !"handoff/next.md".equals(artifactName)
                || !artifactUri.startsWith("s3://")
                || digest.isBlank()
                || bytes <= 0L) {
            return;
        }
        String evidenceType = handoffEvidenceType(role);
        if (evidenceType.isBlank()) {
            return;
        }
        String summary = firstNonBlank(
                bounded(handoff.path("summary").asText(""), 600),
                "Controlled handoff document from " + sourceRole
        );
        evidence.add(new RoleContextEvidence(
                "role-handoff-" + digest,
                "ROLE_HANDOFF",
                artifactUri,
                "Handoff from " + sourceRole,
                "sha256:" + digest,
                summary,
                collectedAtEpochMillis,
                "directed handoff manifest; content verified before executor attachment",
                1.0d,
                evidenceType,
                false
        ));
    }

    private String handoffEvidenceType(AgentRole role) {
        return switch (role) {
            case SOLUTION_ARCHITECT -> "ARCHITECTURE";
            case CODING_AGENT -> "CODE_SYMBOL";
            case QA_AGENT -> "TEST_ENTRY";
            default -> "";
        };
    }

    private String normalizeSha256(String value) {
        String normalized = safe(value);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        return normalized.matches("[0-9a-fA-F]{64}") ? normalized.toLowerCase(Locale.ROOT) : "";
    }

    private List<RoleContextEvidence> mergeCandidates(
            List<RoleContextEvidence> roots,
            List<RoleContextEvidence> searched,
            List<RoleContextEvidence> handoffs
    ) {
        List<RoleContextEvidence> merged = new ArrayList<>(roots);
        if (searched != null) {
            merged.addAll(searched);
        }
        if (handoffs != null) {
            merged.addAll(handoffs);
        }
        return deduplicate(merged);
    }

    private List<RoleContextEvidence> deduplicate(List<RoleContextEvidence> evidence) {
        Map<String, RoleContextEvidence> unique = new LinkedHashMap<>();
        for (RoleContextEvidence item : evidence == null ? List.<RoleContextEvidence>of() : evidence) {
            if (!valid(item)) {
                continue;
            }
            String key = firstNonBlank(item.contentHash(), item.sourceUri(), item.evidenceId());
            unique.putIfAbsent(key, item);
        }
        return List.copyOf(unique.values());
    }

    private boolean valid(RoleContextEvidence evidence) {
        return evidence != null
                && !evidence.evidenceId().isBlank()
                && !evidence.sourceUri().isBlank()
                && !evidence.contentHash().isBlank()
                && !evidence.summary().isBlank();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
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

    private static RetrievalOutcome withStopReason(RetrievalOutcome outcome, String stopReason) {
        return new RetrievalOutcome(
                outcome.runId(),
                outcome.status(),
                outcome.consumerType(),
                outcome.role(),
                outcome.stageRunId(),
                outcome.selectedEvidence(),
                outcome.qualityDecision(),
                outcome.qualityReportArtifactId(),
                outcome.missingEvidenceTypes(),
                outcome.omittedEvidenceIds(),
                stopReason == null || stopReason.isBlank() ? outcome.stopReason() : stopReason.strip()
        );
    }

    private static String refineClues(String clues, List<String> missingEvidenceTypes) {
        String base = clues == null ? "" : clues.strip();
        if (missingEvidenceTypes == null || missingEvidenceTypes.isEmpty()) {
            return base;
        }
        String missing = String.join(",", missingEvidenceTypes);
        String suffix = "missingEvidenceTypes=" + missing;
        if (base.contains(suffix)) {
            return base;
        }
        return base.isBlank() ? suffix : base + "; " + suffix;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record Selection(List<RoleContextEvidence> selected, List<String> omittedIds) {
    }
}
