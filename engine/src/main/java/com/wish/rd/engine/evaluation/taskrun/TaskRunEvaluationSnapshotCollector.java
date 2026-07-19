package com.wish.rd.engine.evaluation.taskrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Builds a bounded, redacted, task-scoped evaluation record from persistent control-plane stores. */
@Component
public final class TaskRunEvaluationSnapshotCollector {
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() { };
    private static final int MAX_PREVIEW_CHARS = 2_000;
    private static final int MAX_STAGE_ATTEMPTS = 40;
    private static final int MAX_ARTIFACTS = 40;
    private static final int MAX_CONTEXTS = 40;
    private static final int MAX_TIMELINE_EVENTS = 200;
    private static final int MAX_RETRIEVAL_RUNS = 40;
    private static final int MAX_RETRIEVAL_ARTIFACTS_PER_RUN = 40;
    private static final int MAX_RETRIEVED_CONTEXTS = 40;
    private static final int MAX_NESTED_COLLECTION_ITEMS = 50;
    private static final int MAX_TOTAL_CHARS = 500_000;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(authorization|cookie|password|secret|access[_-]?token|api[_-]?key|token).*");
    private static final Pattern COMMIT_SHA = Pattern.compile("(?i)[0-9a-f]{7,64}");

    private final RagStreamTaskRegistry registry;
    private final AgentStageRunStore stageRunStore;
    private final AgentStageArtifactStore artifactStore;
    private final RoleContextPackageStore contextStore;
    private final RetrievalRunStore retrievalStore;
    private final WorkflowExperienceStore experienceStore;
    private final ObjectMapper objectMapper;

    public TaskRunEvaluationSnapshotCollector(
            RagStreamTaskRegistry registry,
            AgentStageRunStore stageRunStore,
            AgentStageArtifactStore artifactStore,
            RoleContextPackageStore contextStore,
            RetrievalRunStore retrievalStore,
            WorkflowExperienceStore experienceStore,
            ObjectMapper objectMapper
    ) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry must not be null");
        this.stageRunStore = java.util.Objects.requireNonNull(stageRunStore, "stageRunStore must not be null");
        this.artifactStore = java.util.Objects.requireNonNull(artifactStore, "artifactStore must not be null");
        this.contextStore = java.util.Objects.requireNonNull(contextStore, "contextStore must not be null");
        this.retrievalStore = java.util.Objects.requireNonNull(retrievalStore, "retrievalStore must not be null");
        this.experienceStore = java.util.Objects.requireNonNull(experienceStore, "experienceStore must not be null");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Returns an evaluable target and rejects tasks that have not produced execution evidence yet. */
    public TaskRunEvaluationTarget target(String taskId) {
        RdTask task = registry.getTask(taskId);
        List<AgentStageRun> stages = stageRunStore.listByTask(task.taskId());
        List<RdTaskStatusEvent> timeline = registry.timeline(task.taskId());
        if (stages.isEmpty() && timeline.size() <= 1) {
            throw new IllegalStateException("task has no execution evidence to evaluate: " + task.taskId());
        }
        return new TaskRunEvaluationTarget(task.taskId(), task.taskType(), task.title(), task.status().name());
    }

    /** Creates one generated dataset row and its matching immutable execution record. */
    public TaskRunEvaluationPayload collect(EvaluationRun evaluationRun) {
        String taskId = evaluationRun.config().taskId();
        RdTask task = registry.getTask(taskId);
        List<AgentRole> expectedRoles = "BUG_FIX".equals(task.taskType())
                ? AgentRole.bugFixOrder()
                : AgentRole.requirementDeliveryOrder();
        List<String> expectedRoleNames = expectedRoles.stream().map(AgentRole::name).toList();
        List<String> expectedRetrievalConsumers = "BUG_FIX".equals(task.taskType())
                ? List.of("BUG_FIX")
                : List.of("REQUIREMENT_BASE", "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT");

        List<AgentStageRun> allStageRuns = stageRunStore.listByTask(taskId).stream()
                .sorted(Comparator.comparing((AgentStageRun run) -> run.role().ordinal())
                        .thenComparingInt(AgentStageRun::attemptNo)
                        .thenComparingLong(AgentStageRun::createTimeEpochMillis))
                .toList();
        List<AgentStageRun> stageRuns = allStageRuns.stream().limit(MAX_STAGE_ATTEMPTS).toList();
        List<AgentStageArtifact> allArtifacts = artifactStore.listByTask(taskId).stream()
                .sorted(Comparator.comparingLong(AgentStageArtifact::createdAtEpochMillis)
                        .thenComparing(AgentStageArtifact::artifactId))
                .toList();
        List<AgentStageArtifact> artifacts = allArtifacts.stream().limit(MAX_ARTIFACTS).toList();
        Map<String, AgentStageArtifact> artifactsById = allArtifacts.stream().collect(Collectors.toMap(
                AgentStageArtifact::artifactId, Function.identity(), (left, right) -> right, LinkedHashMap::new));
        Map<AgentRole, AgentStageRun> latestByRole = new LinkedHashMap<>();
        allStageRuns.forEach(run -> latestByRole.merge(run.role(), run,
                (left, right) -> left.attemptNo() >= right.attemptNo() ? left : right));

        Map<String, Object> latestStages = new LinkedHashMap<>();
        expectedRoles.forEach(role -> {
            AgentStageRun run = latestByRole.get(role);
            if (run != null) {
                latestStages.put(role.name(), stageView(run, artifactsById.get(run.resultArtifactId())));
            }
        });
        List<Map<String, Object>> stageAttempts = stageRuns.stream()
                .map(run -> stageView(run, artifactsById.get(run.resultArtifactId())))
                .toList();

        List<RoleContextPackage> allContexts = contextStore.listByTask(taskId).stream()
                .sorted(Comparator.comparing(RoleContextPackage::role)
                        .thenComparingInt(RoleContextPackage::packageVersion)
                        .thenComparing(RoleContextPackage::packageId))
                .toList();
        List<RoleContextPackage> contexts = allContexts.stream().limit(MAX_CONTEXTS).toList();
        List<Map<String, Object>> contextViews = contexts.stream().map(this::contextView).toList();

        List<RetrievalRun> allRetrievalRuns = retrievalStore.listByTask(taskId).stream()
                .sorted(Comparator.comparingLong(RetrievalRun::createdAtEpochMillis)
                        .thenComparingInt(RetrievalRun::attemptNo))
                .toList();
        List<RetrievalRun> retrievalRuns = allRetrievalRuns.stream().limit(MAX_RETRIEVAL_RUNS).toList();
        List<Map<String, Object>> retrievalViews = new ArrayList<>();
        List<Map<String, Object>> retrievedContexts = new ArrayList<>();
        int availableRetrievalArtifacts = 0;
        int includedRetrievalArtifacts = 0;
        for (RetrievalRun run : retrievalRuns) {
            List<RetrievalRunArtifact> allRunArtifacts = retrievalStore.listArtifacts(run.runId());
            availableRetrievalArtifacts += allRunArtifacts.size();
            List<RetrievalRunArtifact> runArtifacts = allRunArtifacts.stream()
                    .limit(MAX_RETRIEVAL_ARTIFACTS_PER_RUN)
                    .toList();
            includedRetrievalArtifacts += runArtifacts.size();
            retrievalViews.add(retrievalView(run, runArtifacts));
            List<Map<String, Object>> selectedEvidence = selectedEvidenceView(run, runArtifacts, allContexts);
            if (!selectedEvidence.isEmpty() && retrievedContexts.size() < MAX_RETRIEVED_CONTEXTS) {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("runId", run.runId());
                group.put("consumer", consumerKey(run));
                group.put("consumerType", run.consumerType().name());
                group.put("role", run.role());
                group.put("stageRunId", run.stageRunId());
                group.put("status", run.status().name());
                group.put("knowledgeBaseIds", run.knowledgeBaseIds());
                group.put("evidence", selectedEvidence);
                retrievedContexts.add(Map.copyOf(group));
            }
        }

        List<RdTaskStatusEvent> allTimeline = registry.timeline(taskId);
        List<RdTaskStatusEvent> timeline = tail(allTimeline, MAX_TIMELINE_EVENTS);
        List<WorkflowExperienceEntry> experiences = experienceStore.listByTask(taskId);
        List<String> experienceTypes = experiences.stream().map(entry -> entry.experienceType().name()).distinct().toList();
        int testEvidenceCount = Math.toIntExact(allArtifacts.stream()
                .filter(artifact -> "TEST_LOG".equalsIgnoreCase(artifact.artifactType()))
                .filter(artifact -> artifact.role() == AgentRole.CODING_AGENT || artifact.role() == AgentRole.QA_AGENT)
                .filter(artifact -> !artifact.contentHash().isBlank() && !artifact.artifactUri().isBlank())
                .count());
        String pullRequestUrl = redact(pullRequestUrl(task));
        List<String> acceptanceCriteria = task instanceof RdRequirementTask requirement
                ? boundedStrings(jsonStringList(requirement.acceptanceCriteriaJson()), 20, 500) : List.of();

        Map<String, Object> truncation = new LinkedHashMap<>();
        truncation.put("stageAttempts", counts(allStageRuns.size(), stageRuns.size()));
        truncation.put("artifacts", counts(allArtifacts.size(), artifacts.size()));
        truncation.put("contexts", counts(allContexts.size(), contexts.size()));
        truncation.put("timeline", counts(allTimeline.size(), timeline.size()));
        truncation.put("retrievalRuns", counts(allRetrievalRuns.size(), retrievalRuns.size()));
        truncation.put("retrievalArtifacts", counts(availableRetrievalArtifacts, includedRetrievalArtifacts));
        truncation.put("totalBudgetApplied", false);

        Map<String, Object> taskRun = new LinkedHashMap<>();
        taskRun.put("taskStatus", task.status().name());
        taskRun.put("taskType", task.taskType());
        taskRun.put("stageAttempts", stageAttempts);
        taskRun.put("contexts", contextViews);
        taskRun.put("artifacts", artifacts.stream().map(this::artifactView).toList());
        taskRun.put("timeline", timeline.stream().map(this::eventView).toList());
        taskRun.put("retrievalRuns", retrievalViews);
        taskRun.put("testEvidenceCount", testEvidenceCount);
        taskRun.put("pullRequestUrl", pullRequestUrl);
        taskRun.put("baseBranch", baseBranch(task));
        taskRun.put("workBranch", workBranch(task));
        taskRun.put("commitSha", commitSha(task));
        taskRun.put("acceptanceCriteria", acceptanceCriteria);
        taskRun.put("truncation", truncation);

        String sampleId = "TASK-" + taskId;
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("sample_id", sampleId);
        dataset.put("suite", "task-run");
        dataset.put("scenario", task.title());
        dataset.put("difficulty", "live");
        dataset.put("tags", List.of("task-run", task.taskType().toLowerCase()));
        dataset.put("input", taskInput(task));
        dataset.put("task_run_gold", Map.of(
                "expectedRoles", expectedRoleNames,
                "expectedRetrievalConsumers", expectedRetrievalConsumers));
        dataset.put("delivery_gold", Map.of("requiredExperienceTypes", List.of(
                "REQUIREMENT_REVIEW", "TECHNICAL_DESIGN", "CODE_CHANGE", "QA_REPORT", "DELIVERY_REPORT")));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("run_id", evaluationRun.runId());
        record.put("sample_id", sampleId);
        record.put("status", "RECORDED");
        record.put("suite", "task-run");
        record.put("scenario", task.title());
        record.put("environment_id", evaluationRun.config().environmentId());
        record.put("task_id", taskId);
        record.put("stages", latestStages);
        record.put("task_run", taskRun);
        record.put("delivery", Map.of(
                "experienceTypes", experienceTypes,
                "redacted", experiences.stream().allMatch(WorkflowExperienceEntry::redacted)));
        record.put("alerts", List.of());
        record.put("retrieved_contexts", retrievedContexts);
        record.put("response", bounded(redact(executionResultJson(task)), 20_000));
        record.put("final_status", successful(task.status().name()) ? "success" : "failure");
        record.put("trace_id", taskId);
        Map<String, Object> safeDataset = sanitizeMap(dataset);
        Map<String, Object> safeRecord = sanitizeMap(record);
        enforceTotalBudget(safeRecord);
        safeRecord.put("provenance", Map.of(
                "snapshotSchemaVersion", "task-run-v2",
                "datasetId", evaluationRun.config().datasetId(),
                "datasetSha256", sha256(toJson(safeDataset)),
                "recordPayloadSha256", sha256(toJson(safeRecord)),
                "collector", TaskRunEvaluationSnapshotCollector.class.getName(),
                "collectedAtEpochMillis", evaluationRun.createdAtEpochMillis()
        ));
        return new TaskRunEvaluationPayload(Map.copyOf(safeDataset), Map.copyOf(safeRecord));
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize task-run evaluation snapshot", exception);
        }
    }

    private Map<String, Object> stageView(AgentStageRun run, AgentStageArtifact resultArtifact) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stageRunId", run.stageRunId());
        view.put("role", run.role().name());
        view.put("status", run.status().name());
        view.put("attemptNo", run.attemptNo());
        view.put("contextPackageId", run.contextPackageId());
        view.put("promptArtifactId", run.promptArtifactId());
        view.put("resultArtifactId", run.resultArtifactId());
        view.put("resultArtifactPresent", resultArtifact != null
                && run.resultArtifactId().equals(resultArtifact.artifactId())
                && !resultArtifact.contentHash().isBlank());
        view.put("providerName", run.providerName());
        view.put("providerAttempts", jsonMapList(redact(run.providerAttemptsJson())));
        view.put("result", resultArtifact == null ? Map.of() : jsonOrPreview(resultArtifact.contentPreview()));
        view.put("errorCategory", run.errorCategory());
        view.put("errorMessage", bounded(redact(run.errorMessage()), 1_000));
        view.put("startedAtEpochMillis", run.startedAtEpochMillis());
        view.put("finishedAtEpochMillis", run.finishedAtEpochMillis());
        return Map.copyOf(view);
    }

    private Map<String, Object> artifactView(AgentStageArtifact artifact) {
        return Map.of(
                "artifactId", artifact.artifactId(),
                "stageRunId", artifact.stageRunId(),
                "role", artifact.role().name(),
                "artifactType", artifact.artifactType(),
                "artifactUri", bounded(redact(artifact.artifactUri()), 500),
                "summary", bounded(redact(artifact.summary()), 500),
                "contentPreview", bounded(redact(artifact.contentPreview()), MAX_PREVIEW_CHARS),
                "contentHash", artifact.contentHash(),
                "createdAtEpochMillis", artifact.createdAtEpochMillis());
    }

    private Map<String, Object> contextView(RoleContextPackage context) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("packageId", context.packageId());
        view.put("role", context.role());
        view.put("packageVersion", context.packageVersion());
        view.put("retrievalRunId", context.retrievalRunId());
        view.put("evidenceCount", context.evidence().size());
        view.put("evidence", context.evidence().stream().map(this::contextEvidenceView).toList());
        view.put("acceptanceCriteria", boundedStrings(context.acceptanceCriteria(), 20, 500));
        view.put("maxChars", context.maxChars());
        view.put("usedChars", context.usedChars());
        view.put("omittedEvidenceIds", boundedStrings(context.omittedEvidenceIds(), 50, 200));
        return Map.copyOf(view);
    }

    private Map<String, Object> retrievalView(RetrievalRun run, List<RetrievalRunArtifact> artifacts) {
        List<Map<String, Object>> selected = artifacts.stream()
                .filter(artifact -> "SELECTED_EVIDENCE".equals(artifact.artifactType()))
                .map(this::retrievalEvidenceArtifactView)
                .toList();
        String qualityHash = artifacts.stream()
                .filter(artifact -> "QUALITY_REPORT".equals(artifact.artifactType()))
                .map(RetrievalRunArtifact::contentHash)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        long scopeViolationCount = artifacts.stream()
                .filter(artifact -> "SCOPE_VIOLATION".equals(artifact.artifactType()))
                .count();
        return Map.ofEntries(
                Map.entry("runId", run.runId()),
                Map.entry("consumerKey", consumerKey(run)),
                Map.entry("consumerType", run.consumerType().name()),
                Map.entry("role", run.role()),
                Map.entry("stageRunId", run.stageRunId()),
                Map.entry("status", run.status().name()),
                Map.entry("attemptNo", run.attemptNo()),
                Map.entry("iteration", run.currentIteration()),
                Map.entry("knowledgeBaseIds", run.knowledgeBaseIds()),
                Map.entry("candidateCount", run.candidateCount()),
                Map.entry("selectedEvidenceCount", run.selectedEvidenceCount()),
                Map.entry("qualityDecision", run.qualityDecision() == null ? "" : run.qualityDecision().name()),
                Map.entry("stopReason", bounded(redact(run.stopReason()), 1_000)),
                Map.entry("selectedEvidenceArtifacts", selected),
                Map.entry("qualityReportHash", qualityHash),
                Map.entry("scopeViolationCount", scopeViolationCount),
                Map.entry("artifactCount", artifacts.size()));
    }

    private List<Map<String, Object>> selectedEvidenceView(
            RetrievalRun run,
            List<RetrievalRunArtifact> artifacts,
            List<RoleContextPackage> contexts
    ) {
        List<RoleContextEvidence> boundEvidence = contexts.stream()
                .filter(context -> context.retrievalRunId().equals(run.runId())
                        || (context.retrievalRunId().isBlank() && context.role().equals(run.role())))
                .flatMap(context -> context.evidence().stream())
                .toList();
        return artifacts.stream()
                .filter(artifact -> "SELECTED_EVIDENCE".equals(artifact.artifactType()))
                .limit(MAX_RETRIEVED_CONTEXTS)
                .map(artifact -> {
                    RoleContextEvidence match = boundEvidence.stream()
                            .filter(evidence -> evidence.contentHash().equals(artifact.contentHash())
                                    || evidence.sourceUri().equals(artifact.artifactUri()))
                            .findFirst()
                            .orElse(null);
                    Map<String, Object> view = new LinkedHashMap<>(retrievalEvidenceArtifactView(artifact));
                    if (match != null) {
                        view.put("evidenceId", match.evidenceId());
                        view.put("sourceType", match.sourceType());
                        view.put("title", match.title());
                        view.put("selectionReason", match.selectionReason());
                        view.put("relevanceScore", match.relevanceScore());
                        view.put("requiredEvidenceType", match.requiredEvidenceType());
                        view.put("sharedRoot", match.sharedRoot());
                    }
                    return Map.copyOf(view);
                })
                .toList();
    }

    private Map<String, Object> retrievalEvidenceArtifactView(RetrievalRunArtifact artifact) {
        return Map.of(
                "artifactId", artifact.artifactId(),
                "sourceUri", bounded(redact(artifact.artifactUri()), 500),
                "contentHash", artifact.contentHash(),
                "contentPreview", bounded(redact(artifact.contentPreview()), MAX_PREVIEW_CHARS));
    }

    private Map<String, Object> contextEvidenceView(RoleContextEvidence evidence) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("evidenceId", evidence.evidenceId());
        view.put("sourceType", evidence.sourceType());
        view.put("sourceUri", bounded(redact(evidence.sourceUri()), 500));
        view.put("title", bounded(redact(evidence.title()), 500));
        view.put("contentHash", evidence.contentHash());
        view.put("summary", bounded(redact(evidence.summary()), MAX_PREVIEW_CHARS));
        view.put("selectionReason", bounded(redact(evidence.selectionReason()), 500));
        view.put("relevanceScore", evidence.relevanceScore());
        view.put("requiredEvidenceType", evidence.requiredEvidenceType());
        view.put("sharedRoot", evidence.sharedRoot());
        return Map.copyOf(view);
    }

    private String consumerKey(RetrievalRun run) {
        return "AGENT_ROLE".equals(run.consumerType().name()) && !run.role().isBlank()
                ? run.role() : run.consumerType().name();
    }

    private Map<String, Object> eventView(RdTaskStatusEvent event) {
        return Map.of(
                "eventId", event.id(),
                "status", event.status(),
                "message", bounded(redact(event.message()), 500),
                "trigger", event.trigger(),
                "enteredAtEpochMillis", event.enteredAtEpochMillis(),
                "durationMillis", event.durationMillis());
    }

    private Map<String, Object> taskInput(RdTask task) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskId", task.taskId());
        input.put("taskType", task.taskType());
        input.put("title", task.title());
        input.put("priority", task.priority());
        if (task instanceof RdRequirementTask requirement) {
            input.put("repositoryUrl", bounded(redact(requirement.repositoryUrl()), 500));
            input.put("baseBranch", requirement.baseBranch());
            input.put("expectedResult", bounded(redact(requirement.expectedResult()), 10_000));
            input.put("acceptanceCriteria", boundedStrings(jsonStringList(requirement.acceptanceCriteriaJson()), 20, 500));
        } else if (task instanceof RdBugFixTask bugFix) {
            input.put("repositoryUrl", bounded(redact(bugFix.repositoryUrl()), 500));
            input.put("baseBranch", bugFix.baseBranch());
            input.put("ticketId", bugFix.ticketId());
            input.put("ticketTitle", bugFix.ticketTitle());
        }
        return Map.copyOf(input);
    }

    private Object jsonOrPreview(String value) {
        String safeValue = bounded(redact(value), MAX_PREVIEW_CHARS);
        if (safeValue.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(safeValue, Object.class);
        } catch (JsonProcessingException ignored) {
            return Map.of("contentPreview", safeValue);
        }
    }

    private List<Map<String, Object>> jsonMapList(String value) {
        try {
            return objectMapper.readValue(value, MAP_LIST);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private List<String> jsonStringList(String value) {
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private static boolean successful(String status) {
        return "COMMITTED".equals(status) || "COMPLETED".equals(status) || "MERGED".equals(status);
    }

    private static String pullRequestUrl(RdTask task) {
        if (task instanceof RdRequirementTask requirement) {
            return requirement.pullRequestUrl();
        }
        if (task instanceof RdBugFixTask bugFix) {
            return bugFix.pullRequestUrl();
        }
        return "";
    }

    private static String executionResultJson(RdTask task) {
        if (task instanceof RdRequirementTask requirement) {
            return requirement.executionResultJson();
        }
        if (task instanceof RdBugFixTask bugFix) {
            return bugFix.executionResultJson();
        }
        return "";
    }

    private static String baseBranch(RdTask task) {
        if (task instanceof RdRequirementTask requirement) {
            return requirement.baseBranch();
        }
        if (task instanceof RdBugFixTask bugFix) {
            return bugFix.baseBranch();
        }
        return "";
    }

    private String workBranch(RdTask task) {
        if (task instanceof RdRequirementTask requirement && !requirement.workBranch().isBlank()) {
            return requirement.workBranch();
        }
        return resultMetadata(task, List.of("workBranch", "work_branch", "headBranch", "head_branch", "branch"));
    }

    private String commitSha(RdTask task) {
        String value = resultMetadata(task, List.of("commitSha", "commit_sha", "headSha", "head_sha", "commit"));
        return COMMIT_SHA.matcher(value).matches() ? value : "";
    }

    private String resultMetadata(RdTask task, List<String> keys) {
        String value = executionResultJson(task);
        if (value.isBlank()) {
            return "";
        }
        try {
            return findMetadataValue(objectMapper.readValue(value, Object.class), keys);
        } catch (JsonProcessingException ignored) {
            return "";
        }
    }

    private String findMetadataValue(Object value, List<String> keys) {
        if (value instanceof Map<?, ?> map) {
            for (String key : keys) {
                Object direct = map.get(key);
                if (direct != null && !String.valueOf(direct).isBlank()) {
                    return bounded(redact(String.valueOf(direct)), 500);
                }
            }
            for (Object child : map.values()) {
                String nested = findMetadataValue(child, keys);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                String nested = findMetadataValue(child, keys);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : digest) {
                output.append(String.format(java.util.Locale.ROOT, "%02x", item));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private static String redact(String value) {
        String normalized = value == null ? "" : value;
        return normalized
                .replaceAll("(?i)(https?://)([A-Za-z0-9._~+%-]+):([A-Za-z0-9._~+%=-]+)@", "$1<redacted>@")
                .replaceAll("(?i)([?&](?:access_token|token|api[_-]?key|secret|password)=)[^&#\\s]+", "$1<redacted>")
                .replaceAll("(?i)(authorization\\s*[=:]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>")
                .replaceAll("(?i)([\\\"]?(?:token|api[_-]?key|password|secret|cookie)[\\\"]?\\s*[=:]\\s*)[\\\"]?[^\\s,;}\\\"]+[\\\"]?", "$1\\\"<redacted>\\\"")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1<redacted>");
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> sanitized.put(key, sanitizeTree(key, value)));
        return sanitized;
    }

    private Object sanitizeTree(String key, Object value) {
        if (SENSITIVE_KEY.matcher(key == null ? "" : key).matches()) {
            return "<redacted>";
        }
        if (value instanceof String text) {
            return bounded(redact(text), MAX_PREVIEW_CHARS);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.entrySet().stream().limit(MAX_NESTED_COLLECTION_ITEMS).forEach(entry -> {
                String childKey = String.valueOf(entry.getKey());
                sanitized.put(childKey, sanitizeTree(childKey, entry.getValue()));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(MAX_NESTED_COLLECTION_ITEMS)
                    .map(item -> sanitizeTree("", item))
                    .toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private void enforceTotalBudget(Map<String, Object> record) {
        if (toJson(record).length() <= MAX_TOTAL_CHARS) {
            return;
        }
        Map<String, Object> taskRun = (Map<String, Object>) record.get("task_run");
        taskRun.put("artifacts", listOfMaps(taskRun.get("artifacts")).stream().map(artifact -> {
            Map<String, Object> metadata = new LinkedHashMap<>(artifact);
            metadata.put("contentPreview", "");
            metadata.put("summary", bounded(String.valueOf(metadata.getOrDefault("summary", "")), 200));
            return metadata;
        }).toList());
        record.put("retrieved_contexts", List.of());
        record.put("response", "");
        Map<String, Object> truncation = (Map<String, Object>) taskRun.get("truncation");
        truncation.put("totalBudgetApplied", true);
        if (toJson(record).length() > MAX_TOTAL_CHARS) {
            taskRun.put("stageAttempts", listOfMaps(taskRun.get("stageAttempts")).stream().limit(20).toList());
            taskRun.put("timeline", listOfMaps(taskRun.get("timeline")).stream().limit(100).toList());
        }
        if (toJson(record).length() > MAX_TOTAL_CHARS) {
            throw new IllegalStateException("task-run evaluation snapshot exceeds bounded output budget");
        }
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static List<String> boundedStrings(List<String> values, int maxItems, int maxChars) {
        return values.stream().limit(maxItems).map(value -> bounded(redact(value), maxChars)).toList();
    }

    private static Map<String, Integer> counts(int available, int included) {
        return Map.of("available", available, "included", included);
    }

    private static <T> List<T> tail(List<T> values, int limit) {
        return values.size() <= limit ? values : values.subList(values.size() - limit, values.size());
    }

    public record TaskRunEvaluationTarget(String taskId, String taskType, String title, String status) { }

    public record TaskRunEvaluationPayload(Map<String, Object> dataset, Map<String, Object> record) { }
}
