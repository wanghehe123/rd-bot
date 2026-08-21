package com.wish.rd.bootstrap.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityCommandRow;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityEventRow;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityStageRow;
import com.wish.rd.bootstrap.persistence.mapper.DeliveryObservabilityMapper.DeliveryObservabilityTaskRow;
import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import com.wish.rd.engine.admin.observability.DeliveryObservabilitySnapshotPort;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.AttemptUsage;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.CommandObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StageObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.StatusEventObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot.TaskObservation;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PostgreSQL adapter for delivery observability. Aggregation stays in engine;
 * this class only loads window/project constrained ledger rows.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresDeliveryObservabilitySnapshotAdapter implements DeliveryObservabilitySnapshotPort {

    private static final Logger log = LoggerFactory.getLogger(PostgresDeliveryObservabilitySnapshotAdapter.class);

    private static final Set<String> TERMINAL = Set.of(
            "COMPLETED", "COMMITTED", "MERGED", "REJECTED", "FAILED_RETRYABLE",
            "FAILED_NEEDS_HUMAN", "DEAD_LETTERED", "CANCELLED");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DeliveryObservabilityMapper mapper;
    private final ObjectProvider<RequirementDeliveryDispatchService> dispatchService;

    /**
     * @param mapper read mapper
     * @param dispatchService live scheduler; optional
     */
    public PostgresDeliveryObservabilitySnapshotAdapter(
            DeliveryObservabilityMapper mapper,
            ObjectProvider<RequirementDeliveryDispatchService> dispatchService
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.dispatchService = dispatchService;
    }

    @Override
    public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
        Instant generatedAt = Instant.now();
        try {
            String projectId = query.allProjects() ? null : query.projectId();
            List<DeliveryObservabilityTaskRow> taskRows = mapper.listTasks(
                    projectId, query.windowStart(), query.now());
            Map<String, List<StatusEventObservation>> events = new LinkedHashMap<>();
            Map<String, List<StageObservation>> stages = new LinkedHashMap<>();
            for (DeliveryObservabilityEventRow row : mapper.listStatusEvents(
                    projectId, query.windowStart(), query.now())) {
                events.computeIfAbsent(row.taskId, ignored -> new ArrayList<>())
                        .add(new StatusEventObservation(row.status, row.enteredAt, row.durationMs));
            }
            for (DeliveryObservabilityStageRow row : mapper.listStages(
                    projectId, query.windowStart(), query.now())) {
                stages.computeIfAbsent(row.taskId, ignored -> new ArrayList<>())
                        .add(new StageObservation(
                                row.stageRunId, row.role, row.status, row.attemptNo,
                                row.startedAt, row.finishedAt, row.errorCategory,
                                parseAttempts(row.providerAttemptsJson)));
            }
            List<TaskObservation> tasks = taskRows.stream()
                    .map(row -> toTask(row, events.getOrDefault(row.taskId, List.of()),
                            stages.getOrDefault(row.taskId, List.of())))
                    .toList();
            List<CommandObservation> commands = mapper.listWaitingCommands(projectId).stream()
                    .map(PostgresDeliveryObservabilitySnapshotAdapter::toCommand)
                    .toList();
            return new DeliveryLedgerSnapshot(true, generatedAt, "", tasks, commands);
        } catch (RuntimeException exception) {
            log.warn("delivery ledger query failed", exception);
            return DeliveryLedgerSnapshot.failed(generatedAt, "delivery ledger query failed");
        }
    }

    @Override
    public RequirementDeliveryMetrics.Snapshot loadScheduler() {
        RequirementDeliveryDispatchService dispatcher = dispatchService == null
                ? null : dispatchService.getIfAvailable();
        if (dispatcher == null) {
            return null;
        }
        try {
            return dispatcher.metricsSnapshot();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static TaskObservation toTask(
            DeliveryObservabilityTaskRow row,
            List<StatusEventObservation> events,
            List<StageObservation> stages
    ) {
        Instant acceptedAt = row.createdAt;
        Instant terminalAt = TERMINAL.contains(safe(row.status)) ? row.updatedAt : null;
        return new TaskObservation(
                row.taskId,
                row.projectId,
                row.title,
                row.status,
                acceptedAt,
                terminalAt,
                row.pullRequestUrl,
                "",
                events,
                stages
        );
    }

    private static CommandObservation toCommand(DeliveryObservabilityCommandRow row) {
        return new CommandObservation(
                row.commandId,
                row.status,
                row.resourceClass,
                row.createdAt,
                true
        );
    }

    private static List<AttemptUsage> parseAttempts(String json) {
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json.strip())) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<AttemptUsage> attempts = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            int index = 0;
            for (JsonNode node : root) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                String attemptId = text(node, "attemptId");
                if (attemptId.isBlank()) {
                    attemptId = text(node, "provider") + "-" + index;
                }
                index++;
                if (!attemptId.isBlank() && !seen.add(attemptId)) {
                    continue;
                }
                attempts.add(new AttemptUsage(
                        attemptId,
                        text(node, "runtime"),
                        text(node, "provider"),
                        longValue(node, "inputTokens"),
                        longValue(node, "outputTokens"),
                        firstLong(node, "cacheReadInputTokens", "cacheTokens"),
                        costCny(node),
                        node.path("tokenUsageAvailable").asBoolean(false)
                                || node.has("inputTokens")
                                || node.has("outputTokens"),
                        millis(node, "firstTokenMillis", "firstTokenAvailable"),
                        millis(node, "firstProviderResponseMillis", "firstProviderResponseAvailable")
                ));
            }
            return List.copyOf(attempts);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0L : value.asLong(0L);
    }

    private static long firstLong(JsonNode node, String first, String second) {
        if (node.has(first) && !node.get(first).isNull()) {
            return node.get(first).asLong(0L);
        }
        if (node.has(second) && !node.get(second).isNull()) {
            return node.get(second).asLong(0L);
        }
        return 0L;
    }

    private static double costCny(JsonNode node) {
        for (String key : List.of("estimatedSpendCny", "estimatedCostCny")) {
            if (node.has(key) && !node.get(key).isNull() && !node.get(key).asText("").isBlank()) {
                try {
                    return Double.parseDouble(node.get(key).asText().strip());
                } catch (NumberFormatException ignored) {
                    return Double.NaN;
                }
            }
        }
        return Double.NaN;
    }

    private static long millis(JsonNode node, String field, String availableField) {
        if (node.has(availableField) && !node.get(availableField).asBoolean(true)) {
            return -1L;
        }
        if (!node.has(field) || node.get(field).isNull()) {
            return -1L;
        }
        long value = node.get(field).asLong(-1L);
        return value < 0L ? -1L : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
