package com.wish.rd.engine.scheduling;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.model.FairScheduleLimits;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.engine.scheduling.model.StageScheduleCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Virtual-time acceptance test for a mixed 100-command stage queue.
 *
 * <p>The loop deliberately models the persistence boundary rather than invoking an executor:
 * claims create in-flight leases, completions release project/resource capacity, and provider
 * failures re-enter the queue with a bounded visibility delay. This keeps the test deterministic
 * while exercising the same planner and claimability rules used by dispatch.
 */
class RequirementFairSchedulingSimulationTest {

    private static final long MINUTE = 60_000L;
    private static final long HALF_HOUR = 30 * MINUTE;
    private static final long HOUR = 60 * MINUTE;
    private static final long TICK = 5 * MINUTE;

    @Test
    void servesEveryProjectWithBoundedPriorityLatencyAndBackpressure() {
        long start = 0L;
        List<RequirementStageCommand> queue = new ArrayList<>();
        Map<String, Long> durationByCommand = new HashMap<>();
        Set<String> transientProviderFailures = new HashSet<>();
        Set<String> projects = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            String project = "project-" + (index % 5);
            String priority = index % 10 < 2 ? "P0" : index % 10 < 6 ? "P1" : "P2";
            String stage = switch (index % 6) {
                case 0 -> "provider-call";
                case 1 -> "docker-execute";
                case 2 -> "browser-qa";
                default -> "host-plan";
            };
            String commandId = "command-" + index;
            queue.add(RequirementStageCommand.pending(
                    commandId, "task-" + index, 1L, 1L, "CODING_AGENT", stage,
                    0, 5, 0L, ScheduleResourceClass.GENERIC, project, "provider-a",
                    priority, start));
            durationByCommand.put(commandId, durationFor(priority));
            // Two bounded transient failures exercise both rate-limit and upstream-error paths.
            if (stage.equals("provider-call") && (index == 0 || index == 18)) {
                transientProviderFailures.add(commandId);
            }
            projects.add(project);
        }

        FairScheduleLimits limits = new FairScheduleLimits(2, 2, 2, 3, 10, HOUR);
        Map<String, Integer> projectWeights = Map.of(
                "project-0", 2,
                "project-1", 1,
                "project-2", 1,
                "project-3", 1,
                "project-4", 1);
        FairRequirementDeliveryClaimPlanner planner = new FairRequirementDeliveryClaimPlanner();
        RequirementDeliveryMetrics metrics = new RequirementDeliveryMetrics();
        queue.forEach(command -> metrics.recordEnqueued(command, start));

        List<RunningCommand> running = new ArrayList<>();
        Map<String, Long> firstClaimAt = new HashMap<>();
        Map<String, Integer> failureCount = new HashMap<>();
        Map<String, Integer> projectClaims = new HashMap<>();
        int maxQueueSize = queue.size();
        int maxRetryBurst = 0;
        long now = start;
        int ticks = 0;
        while ((!queue.isEmpty() || !running.isEmpty()) && ticks++ < 2_000) {
            int retryBurst = completeDueCommands(
                    running, queue, transientProviderFailures, failureCount, now, metrics);
            maxRetryBurst = Math.max(maxRetryBurst, retryBurst);

            Map<ScheduleResourceClass, Integer> resourceLoad = resourceLoad(running);
            metrics.recordInFlight(resourceLoad, limits);
            List<RequirementStageCommand> inFlight = running.stream()
                    .map(RunningCommand::command)
                    .toList();
            List<RequirementStageCommand> selected = planner.planStageCommands(
                    queue, inFlight, limits, now, projectWeights);
            Set<String> selectedIds = selected.stream()
                    .map(RequirementStageCommand::commandId)
                    .collect(java.util.stream.Collectors.toSet());
            queue.removeIf(command -> selectedIds.contains(command.commandId()));
            for (RequirementStageCommand command : selected) {
                long finishAt = now + durationByCommand.get(command.commandId());
                RequirementStageCommand claimed = command.claimed(
                        "simulation-worker", finishAt + TICK, now);
                running.add(new RunningCommand(claimed, finishAt));
                firstClaimAt.putIfAbsent(command.commandId(), now);
                projectClaims.merge(command.projectId(), 1, Integer::sum);
                metrics.recordClaimed(claimed, now);
            }
            maxQueueSize = Math.max(maxQueueSize, queue.size());
            now += TICK;
        }

        assertTrue(ticks < 2_000, "the bounded scheduler simulation must drain");
        assertTrue(queue.isEmpty(), "all commands should leave the durable queue");
        assertTrue(running.isEmpty(), "all leased commands should complete");
        assertEquals(100, firstClaimAt.size(), "every command must receive service");
        assertEquals(projects, projectClaims.keySet(), "every active project must receive service");
        assertTrue(
                firstClaimAt.entrySet().stream()
                        .filter(entry -> entry.getKey().equals("command-0")
                                || entry.getKey().equals("command-1")
                                || entry.getKey().equals("command-10")
                                || entry.getKey().equals("command-11"))
                        .mapToLong(Map.Entry::getValue)
                        .max()
                        .orElse(0L) <= 2 * HOUR,
                "P0 work must not wait behind the two-hour P2 tasks");
        assertTrue(
                firstClaimAt.entrySet().stream()
                        .filter(entry -> Integer.parseInt(entry.getKey().substring("command-".length())) % 10 >= 6)
                        .mapToLong(Map.Entry::getValue)
                        .max()
                        .orElse(0L) > HOUR,
                "some P2 work should be served after aging has accumulated");
        assertTrue(maxQueueSize <= 100, "retry requeue must not grow the durable queue");
        assertTrue(maxRetryBurst <= limits.maxProvider(), "provider retries must stay rate bounded");
        assertEquals(transientProviderFailures.size() * 2L, metrics.snapshot().retries());

        RequirementDeliveryMetrics.Snapshot snapshot = metrics.snapshot();
        assertTrue(snapshot.queueAgeP50Millis() >= 0L);
        assertTrue(snapshot.queueAgeP95Millis() > 0L);
        assertTrue(snapshot.queueAgeP95Millis() >= snapshot.queueAgeP50Millis());
        assertTrue(snapshot.oldestQueueAgeMillis() >= snapshot.queueAgeP95Millis());
        assertTrue(snapshot.projectWaitRatio() > 0D && snapshot.projectWaitRatio() < 1D);
        assertTrue(snapshot.resourceUtilization().get(ScheduleResourceClass.DOCKER) > 0D);
        assertTrue(snapshot.resourceUtilization().get(ScheduleResourceClass.BROWSER_QA) > 0D);
        assertTrue(snapshot.resourceUtilization().get(ScheduleResourceClass.PROVIDER) > 0D);
        assertFalse(snapshot.resourceUtilization().isEmpty());
    }

    private static int completeDueCommands(
            List<RunningCommand> running,
            List<RequirementStageCommand> queue,
            Set<String> transientProviderFailures,
            Map<String, Integer> failureCount,
            long now,
            RequirementDeliveryMetrics metrics
    ) {
        int retryBurst = 0;
        Iterator<RunningCommand> iterator = running.iterator();
        while (iterator.hasNext()) {
            RunningCommand current = iterator.next();
            if (current.finishAtEpochMillis() > now) {
                continue;
            }
            RequirementStageCommand command = current.command();
            int failures = failureCount.getOrDefault(command.commandId(), 0);
            if (transientProviderFailures.contains(command.commandId()) && failures < 2) {
                failureCount.put(command.commandId(), failures + 1);
                long backoff = Math.min(30 * MINUTE, 15 * MINUTE * (failures + 1L));
                queue.add(retryAt(command.failed(
                        failures == 0 ? "provider 429" : "provider 5xx", now), now + backoff));
                metrics.recordRetry();
                retryBurst++;
            } else {
                metrics.recordCompleted(command, now);
            }
            iterator.remove();
        }
        return retryBurst;
    }

    private static RequirementStageCommand retryAt(
            RequirementStageCommand failed,
            long nextVisibleAtEpochMillis
    ) {
        return new RequirementStageCommand(
                failed.commandId(), failed.taskId(), failed.taskVersion(), failed.fencingToken(),
                failed.role(), failed.stage(), failed.attemptNo(), failed.maxAttempts(),
                failed.deadlineEpochMillis(), failed.resourceClass(), failed.resourceRequirements(), failed.projectId(),
                failed.providerId(), failed.priorityRank(), RequirementStageCommand.Status.FAILED_RETRYABLE,
                "", 0L, nextVisibleAtEpochMillis, failed.lastError(), failed.createdAtEpochMillis(),
                failed.updatedAtEpochMillis());
    }

    private static Map<ScheduleResourceClass, Integer> resourceLoad(List<RunningCommand> running) {
        Map<ScheduleResourceClass, Integer> load = new HashMap<>();
        for (RunningCommand current : running) {
            for (ScheduleResourceClass resource : FairRequirementDeliveryClaimPlanner.resourceRequirementsFor(
                    current.command())) {
                load.merge(resource, 1, Integer::sum);
            }
        }
        return load;
    }

    private static long durationFor(String priority) {
        return switch (priority) {
            case "P0" -> HALF_HOUR;
            case "P1" -> HOUR;
            default -> 2 * HOUR;
        };
    }

    private record RunningCommand(RequirementStageCommand command, long finishAtEpochMillis) {
    }
}
