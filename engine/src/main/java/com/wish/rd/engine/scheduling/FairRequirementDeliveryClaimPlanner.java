package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.scheduling.model.StageScheduleCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Maps durable requirement-delivery jobs onto {@link FairScheduleSelector} for recovery/dispatch ticks.
 * Project ids come from a caller-supplied lookup until jobs carry {@code projectId} natively.
 */
public final class FairRequirementDeliveryClaimPlanner {

    private final FairScheduleSelector selector;

    public FairRequirementDeliveryClaimPlanner(FairScheduleSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
    }

    public FairRequirementDeliveryClaimPlanner() {
        this(new FairScheduleSelector());
    }

    /**
     * Selects an ordered claim batch from recoverable jobs.
     *
     * @param claimable        PENDING / FAILED_RETRYABLE / expired RUNNING candidates
     * @param inFlight         currently RUNNING (non-expired) jobs for load accounting
     * @param projectIdOfTask  resolves fairness key; blank → {@code _default}
     * @param limits           concurrency caps
     * @param nowEpochMillis   ranking clock
     * @return jobs to claim this tick, fair-ordered
     */
    public List<RequirementDeliveryJob> plan(
            List<RequirementDeliveryJob> claimable,
            List<RequirementDeliveryJob> inFlight,
            Function<String, String> projectIdOfTask,
            FairScheduleLimits limits,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        Function<String, String> projects = projectIdOfTask == null
                ? taskId -> "_default"
                : projectIdOfTask;

        Map<String, Integer> projectLoad = new HashMap<>();
        Map<ScheduleResourceClass, Integer> resourceLoad = new HashMap<>();
        if (inFlight != null) {
            for (RequirementDeliveryJob job : inFlight) {
                if (job == null || job.status() != RequirementDeliveryJobStatus.RUNNING) {
                    continue;
                }
                if (job.isExpiredRunning(nowEpochMillis)) {
                    continue;
                }
                String projectId = normalizeProject(projects.apply(job.taskId()));
                projectLoad.merge(projectId, 1, Integer::sum);
                resourceLoad.merge(ScheduleResourceClass.GENERIC, 1, Integer::sum);
            }
        }

        List<StageScheduleCandidate> candidates = new ArrayList<>();
        Map<String, RequirementDeliveryJob> byCommand = new HashMap<>();
        if (claimable != null) {
            for (RequirementDeliveryJob job : claimable) {
                if (job == null) {
                    continue;
                }
                String projectId = normalizeProject(projects.apply(job.taskId()));
                StageScheduleCandidate candidate = new StageScheduleCandidate(
                        job.jobId(),
                        job.taskId(),
                        projectId,
                        Math.max(0, job.attemptNo()),
                        job.createTimeEpochMillis(),
                        ScheduleResourceClass.GENERIC,
                        0L
                );
                candidates.add(candidate);
                byCommand.put(candidate.commandId(), job);
            }
        }

        List<StageScheduleCandidate> selected = selector.select(
                candidates, projectLoad, resourceLoad, limits, nowEpochMillis
        );
        List<RequirementDeliveryJob> planned = new ArrayList<>(selected.size());
        for (StageScheduleCandidate candidate : selected) {
            RequirementDeliveryJob job = byCommand.get(candidate.commandId());
            if (job != null) {
                planned.add(job);
            }
        }
        return List.copyOf(planned);
    }

    /**
     * Plans durable stage commands with project and resource quotas before the persistence adapter
     * performs its bounded SQL claim. Known stage names are classified when an enqueue omitted a
     * resource class, so provider, Docker, and browser work cannot consume generic capacity.
     *
     * @param claimable pending/retryable/expired stage commands
     * @param inFlight non-expired running stage commands
     * @param limits fairness and resource limits
     * @param nowEpochMillis ranking clock
     * @return fair ordered stage commands to claim
     */
    public List<RequirementStageCommand> planStageCommands(
            List<RequirementStageCommand> claimable,
            List<RequirementStageCommand> inFlight,
            FairScheduleLimits limits,
            long nowEpochMillis
    ) {
        return planStageCommands(claimable, inFlight, limits, nowEpochMillis, Map.of());
    }

    /** Plans stage commands with weighted project fair sharing. */
    public List<RequirementStageCommand> planStageCommands(
            List<RequirementStageCommand> claimable,
            List<RequirementStageCommand> inFlight,
            FairScheduleLimits limits,
            long nowEpochMillis,
            Map<String, Integer> projectWeights
    ) {
        Objects.requireNonNull(limits, "limits must not be null");
        Map<String, Integer> projectLoad = new HashMap<>();
        Map<ScheduleResourceClass, Integer> resourceLoad = new HashMap<>();
        Map<String, Integer> providerLoad = new HashMap<>();
        if (inFlight != null) {
            for (RequirementStageCommand command : inFlight) {
                if (command == null || command.status() != RequirementStageCommand.Status.RUNNING
                        || command.leaseUntilEpochMillis() <= nowEpochMillis) {
                    continue;
                }
                String projectId = normalizeProject(command.projectId());
                projectLoad.merge(projectId, 1, Integer::sum);
                addResourceLoad(resourceLoad, resourceRequirementsFor(command));
                if (requiresProvider(command)) {
                    providerLoad.merge(normalizeProvider(command.providerId()), 1, Integer::sum);
                }
            }
        }

        List<StageScheduleCandidate> candidates = new ArrayList<>();
        Map<String, RequirementStageCommand> byCommand = new HashMap<>();
        if (claimable != null) {
            for (RequirementStageCommand command : claimable) {
                if (command == null || !command.claimable(nowEpochMillis)) {
                    continue;
                }
                String projectId = normalizeProject(command.projectId());
                Set<ScheduleResourceClass> requirements = resourceRequirementsFor(command);
                StageScheduleCandidate candidate = new StageScheduleCandidate(
                        command.commandId(), command.taskId(), projectId, command.priorityRank(),
                        command.createdAtEpochMillis(), primaryResource(command, requirements), requirements,
                        command.providerId(), command.taskVersion()
                );
                candidates.add(candidate);
                byCommand.put(candidate.commandId(), command);
            }
        }

        List<StageScheduleCandidate> selected = selector.select(
                candidates, projectLoad, resourceLoad, providerLoad, limits, nowEpochMillis,
                projectWeights == null ? Map.of() : projectWeights
        );
        List<RequirementStageCommand> planned = new ArrayList<>(selected.size());
        for (StageScheduleCandidate candidate : selected) {
            RequirementStageCommand command = byCommand.get(candidate.commandId());
            if (command != null) {
                planned.add(command);
            }
        }
        return List.copyOf(planned);
    }

    /** Classifies known stage names when the persisted resource class is GENERIC. */
    public static ScheduleResourceClass classifyStage(String stage) {
        String normalized = stage == null ? "" : stage.strip().toLowerCase(java.util.Locale.ROOT);
        if ("ai_review".equals(normalized) || "publication".equals(normalized)
                || normalized.startsWith("publication:")) {
            return ScheduleResourceClass.PROVIDER;
        }
        if (normalized.contains("browser") || normalized.contains("playwright")
                || normalized.contains("browser_qa") || normalized.contains("ui-qa")) {
            return ScheduleResourceClass.BROWSER_QA;
        }
        if (normalized.contains("docker") || normalized.contains("container")
                || normalized.contains("build-image") || normalized.contains("workspace")) {
            return ScheduleResourceClass.DOCKER;
        }
        if (normalized.contains("provider") || normalized.contains("model")
                || normalized.contains("llm") || normalized.contains("completion")) {
            return ScheduleResourceClass.PROVIDER;
        }
        return ScheduleResourceClass.GENERIC;
    }

    /**
     * Determines every resource required by a legacy generic command from its role/stage name.
     * New commands persist this set and therefore avoid relying on name inference at claim time.
     */
    public static Set<ScheduleResourceClass> classifyStageRequirements(String role, String stage) {
        String normalizedRole = role == null ? "" : role.strip().toUpperCase(java.util.Locale.ROOT);
        String normalizedStage = stage == null ? "" : stage.strip().toUpperCase(java.util.Locale.ROOT);
        if ("AI_REVIEW".equals(normalizedStage) || "PUBLICATION".equals(normalizedStage)
                || normalizedStage.startsWith("PUBLICATION:")) {
            return Set.of(ScheduleResourceClass.PROVIDER);
        }
        if (normalizedStage.startsWith("ROLE_EXECUTION:")) {
            if (normalizedRole.contains("QA")) {
                return Set.of(
                        ScheduleResourceClass.PROVIDER,
                        ScheduleResourceClass.DOCKER,
                        ScheduleResourceClass.BROWSER_QA
                );
            }
            if (normalizedRole.contains("CODING")) {
                return Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER);
            }
            return Set.of(ScheduleResourceClass.PROVIDER);
        }
        if (normalizedStage.contains("BROWSER") || normalizedStage.contains("PLAYWRIGHT")
                || normalizedStage.contains("UI-QA")) {
            return Set.of(
                    ScheduleResourceClass.PROVIDER,
                    ScheduleResourceClass.DOCKER,
                    ScheduleResourceClass.BROWSER_QA
            );
        }
        if (normalizedStage.contains("DOCKER") || normalizedStage.contains("CONTAINER")
                || normalizedStage.contains("BUILD-IMAGE") || normalizedStage.contains("WORKSPACE")) {
            return Set.of(ScheduleResourceClass.PROVIDER, ScheduleResourceClass.DOCKER);
        }
        if (normalizedStage.contains("PROVIDER") || normalizedStage.contains("MODEL")
                || normalizedStage.contains("LLM") || normalizedStage.contains("COMPLETION")) {
            return Set.of(ScheduleResourceClass.PROVIDER);
        }
        return Set.of(ScheduleResourceClass.GENERIC);
    }

    /** Returns the durable resource requirements, falling back to legacy name classification. */
    public static Set<ScheduleResourceClass> resourceRequirementsFor(RequirementStageCommand command) {
        if (command == null) {
            return Set.of(ScheduleResourceClass.GENERIC);
        }
        Set<ScheduleResourceClass> requirements = command.resourceRequirements();
        if (requirements == null || requirements.isEmpty()
                || requirements.equals(Set.of(ScheduleResourceClass.GENERIC))) {
            return classifyStageRequirements(command.role(), command.stage());
        }
        return requirements;
    }

    private static ScheduleResourceClass primaryResource(
            RequirementStageCommand command,
            Set<ScheduleResourceClass> requirements
    ) {
        if (command.resourceClass() != ScheduleResourceClass.GENERIC) {
            return command.resourceClass();
        }
        return requirements.stream()
                .filter(resource -> resource != ScheduleResourceClass.GENERIC)
                .findFirst()
                .orElse(ScheduleResourceClass.GENERIC);
    }

    private static void addResourceLoad(
            Map<ScheduleResourceClass, Integer> load,
            Set<ScheduleResourceClass> requirements
    ) {
        for (ScheduleResourceClass resource : requirements) {
            load.merge(resource, 1, Integer::sum);
        }
    }

    private static boolean requiresProvider(RequirementStageCommand command) {
        return resourceRequirementsFor(command).contains(ScheduleResourceClass.PROVIDER);
    }

    private static String normalizeProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "_unassigned";
        }
        return providerId.strip();
    }

    private static String normalizeProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return "_default";
        }
        return projectId.strip();
    }
}
