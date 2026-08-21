package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.exec.repair.runtime.AgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionEvent;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
import com.wish.rd.exec.repair.security.SecretRedactor;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteResult;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteStatus;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Keeps the existing live event ring while independently projecting validated PI state events.
 * Invalid or unavailable projections are observation failures and never abort the Agent attempt.
 */
public final class ProjectionAwareAgentExecutionEventStore implements AgentExecutionEventStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectionAwareAgentExecutionEventStore.class);
    private static final String EVENT_PROTOCOL = "rd-agent-event/v1";
    private static final String STATE_PROTOCOL = "rd-agent-state/v2";

    private final AgentExecutionEventStore delegate;
    private final AgentExecutionProfileSnapshotStore snapshots;
    private final AgentStageStateProjectionStore projections;
    private final Consumer<String> warningSink;

    public ProjectionAwareAgentExecutionEventStore(
            AgentExecutionEventStore delegate,
            AgentExecutionProfileSnapshotStore snapshots,
            AgentStageStateProjectionStore projections
    ) {
        this(delegate, snapshots, projections, message -> LOGGER.warn("{}", message));
    }

    public ProjectionAwareAgentExecutionEventStore(
            AgentExecutionEventStore delegate,
            AgentExecutionProfileSnapshotStore snapshots,
            AgentStageStateProjectionStore projections,
            Consumer<String> warningSink
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.warningSink = warningSink == null ? ignored -> { } : warningSink;
    }

    @Override
    public void onEvent(String containerName, JsonNode event) {
        String type = event == null ? "" : event.path("eventType").asText("");
        if ("RUNTIME_STOPPED".equals(type)) {
            delegate.onEvent(containerName, event);
            try {
                AgentExecutionProfileSnapshot frozen = validateEnvelope(event);
                AgentStageProjectionWriteResult result = projections.finalizeProjection(
                        identity(frozen), epoch(event.path("occurredAt"), "occurredAt")
                );
                if (result.status() != AgentStageProjectionWriteStatus.APPLIED
                        && result.status() != AgentStageProjectionWriteStatus.IDEMPOTENT) {
                    warn(type + " projection finalization rejected: "
                            + result.status() + " " + result.reason());
                }
            } catch (RuntimeException exception) {
                warn(type + " projection finalization failed: " + safeError(exception));
            }
            return;
        }
        if (!"STATE_SNAPSHOT_UPDATED".equals(type) && !"STATE_CONTEXT_INJECTED".equals(type)) {
            delegate.onEvent(containerName, event);
            return;
        }
        final ProjectionCommand command;
        try {
            command = validate(event, type);
        } catch (RuntimeException exception) {
            warn(type + " projection rejected: " + safeError(exception));
            return;
        }
        delegate.onEvent(containerName, event.deepCopy());
        try {
            AgentStageProjectionWriteResult result = command.project(projections);
            if (result.status() != AgentStageProjectionWriteStatus.APPLIED
                    && result.status() != AgentStageProjectionWriteStatus.IDEMPOTENT) {
                warn(type + " projection rejected by CAS: " + result.status() + " " + result.reason());
            }
        } catch (RuntimeException exception) {
            warn(type + " projection write failed: " + safeError(exception));
        }
    }

    @Override
    public AgentExecutionTraceSnapshot snapshot(String taskId, String stageRunId, long afterSequence, int limit) {
        return delegate.snapshot(taskId, stageRunId, afterSequence, limit);
    }

    @Override
    public AutoCloseable subscribe(
            String taskId,
            String stageRunId,
            Consumer<AgentExecutionEvent> listener
    ) {
        return delegate.subscribe(taskId, stageRunId, listener);
    }

    private ProjectionCommand validate(JsonNode event, String type) {
        AgentExecutionProfileSnapshot frozen = validateEnvelope(event);
        JsonNode payload = event.path("payload");
        if (!payload.isObject()) throw new IllegalArgumentException("state event payload must be an object");
        return "STATE_SNAPSHOT_UPDATED".equals(type)
                ? validateState(frozen, payload)
                : validateInjection(frozen, payload);
    }

    private AgentExecutionProfileSnapshot validateEnvelope(JsonNode event) {
        if (event == null || !event.isObject() || !EVENT_PROTOCOL.equals(event.path("protocol").asText())) {
            throw new IllegalArgumentException("event protocol is invalid");
        }
        String stageRunId = requiredText(event.path("stageRunId"), "stageRunId");
        AgentExecutionProfileSnapshot frozen = snapshots.findByStageRunId(stageRunId)
                .orElseThrow(() -> new IllegalArgumentException("frozen execution snapshot is unavailable"));
        if (frozen.runtimeType() != AgentRuntimeType.PI
                || !frozen.hasCapability(AgentRuntimeCapability.PI_AGENT_STATE_V2)) {
            throw new IllegalArgumentException("frozen execution snapshot does not enable PI_AGENT_STATE_V2");
        }
        requireEquals(frozen.taskId(), requiredText(event.path("taskId"), "taskId"), "event task identity");
        requireEquals(frozen.stageRunId(), stageRunId, "event stage identity");
        requireEquals(frozen.role(), requiredText(event.path("role"), "role"), "event role identity");
        requireEquals(frozen.snapshotId(), requiredText(event.path("snapshotId"), "snapshotId"),
                "event snapshot identity");
        if (!"PI".equals(event.path("runtimeType").asText())) {
            throw new IllegalArgumentException("event runtime identity does not match PI");
        }
        return frozen;
    }

    private ProjectionCommand validateState(AgentExecutionProfileSnapshot frozen, JsonNode payload) {
        long sequence = nonNegative(payload.path("stateSequence"), "stateSequence");
        String claimedHash = requiredHash(payload.path("stateHash"), "stateHash");
        JsonNode state = payload.path("snapshot");
        if (!state.isObject()) throw new IllegalArgumentException("snapshot must be an object");
        requireStateIdentity(state, frozen);
        if (state.path("sequence").asLong(-1L) != sequence) {
            throw new IllegalArgumentException("snapshot state sequence mismatch");
        }
        String canonical = AgentStateV2Codec.canonicalize(state);
        String actualHash = AgentStateV2Codec.hash(state);
        if (!actualHash.equals(claimedHash)) {
            throw new IllegalArgumentException("snapshot canonical hash mismatch");
        }
        rejectSensitive(canonical);
        long projectedAt = epoch(payload.path("projectedAt"), "projectedAt");
        String expectedIdempotency = "sha256:" + AgentExecutionProfileSnapshot.sha256(
                frozen.stageRunId() + ":" + sequence + ":" + claimedHash
        );
        requireEquals(expectedIdempotency, requiredHash(payload.path("idempotencyKey"), "idempotencyKey"),
                "snapshot idempotency hash");
        AgentStageStateIdentity identity = identity(frozen);
        AgentStateProjectionUpdate update = new AgentStateProjectionUpdate(
                identity, sequence, claimedHash, canonical, projectedAt
        );
        return store -> store.projectState(update);
    }

    private ProjectionCommand validateInjection(AgentExecutionProfileSnapshot frozen, JsonNode payload) {
        long injectionSequence = positive(payload.path("injectionSequence"), "injectionSequence");
        long stateSequence = nonNegative(payload.path("stateSequence"), "stateSequence");
        String stateHash = requiredHash(payload.path("stateHash"), "stateHash");
        String promptHash = requiredHash(payload.path("promptHash"), "promptHash");
        String blockHash = requiredHash(payload.path("blockHash"), "blockHash");
        String block = requiredText(payload.path("injectedBlock"), "injectedBlock");
        if (block.getBytes(StandardCharsets.UTF_8).length != payload.path("bytes").asLong(-1L)) {
            throw new IllegalArgumentException("injected block byte length mismatch");
        }
        if (!("sha256:" + AgentExecutionProfileSnapshot.sha256(block)).equals(blockHash)) {
            throw new IllegalArgumentException("injected block hash mismatch");
        }
        rejectSensitive(block);
        String stateJson = stateJsonFromBlock(block);
        JsonNode state = AgentStateV2Codec.decodeAndVerify(stateJson, stateHash);
        requireStateIdentity(state, frozen);
        if (state.path("sequence").asLong(-1L) != stateSequence) {
            throw new IllegalArgumentException("injected state sequence mismatch");
        }
        String expectedBlock = "<rd-agent-state protocol=\"" + STATE_PROTOCOL + "\" state-sequence=\""
                + stateSequence + "\" injection-sequence=\"" + injectionSequence
                + "\" state-hash=\"" + stateHash + "\">\n" + stateJson + "\n</rd-agent-state>";
        requireEquals(expectedBlock, block, "injected block provenance");
        String expectedIdempotency = "sha256:" + AgentExecutionProfileSnapshot.sha256(
                frozen.stageRunId() + ":" + injectionSequence + ":" + blockHash
        );
        String idempotencyKey = requiredHash(payload.path("idempotencyKey"), "idempotencyKey");
        requireEquals(expectedIdempotency, idempotencyKey, "injection idempotency hash");
        long injectedAt = epoch(payload.path("injectedAt"), "injectedAt");
        AgentContextInjectionProjectionUpdate update = new AgentContextInjectionProjectionUpdate(
                identity(frozen), injectionSequence, stateSequence, stateHash, promptHash,
                blockHash, block, idempotencyKey, injectedAt
        );
        return store -> store.projectInjection(update);
    }

    private static void requireStateIdentity(JsonNode state, AgentExecutionProfileSnapshot frozen) {
        if (!STATE_PROTOCOL.equals(state.path("protocol").asText())) {
            throw new IllegalArgumentException("state protocol is invalid");
        }
        requireEquals(frozen.taskId(), state.path("taskId").asText(), "state task identity");
        requireEquals(frozen.stageRunId(), state.path("stageRunId").asText(), "state stage identity");
        requireEquals(frozen.role(), state.path("role").asText(), "state role identity");
        if (frozen.attemptNo() != state.path("attemptNo").asInt(-1)) {
            throw new IllegalArgumentException("state attempt identity mismatch");
        }
        requireEquals("PI", state.path("runtimeType").asText(), "state runtime identity");
        requireEquals(frozen.snapshotId(), state.path("profileSnapshotId").asText(),
                "state profile identity");
    }

    private static AgentStageStateIdentity identity(AgentExecutionProfileSnapshot frozen) {
        return new AgentStageStateIdentity(
                frozen.taskId(), frozen.stageRunId(), frozen.role(), frozen.attemptNo()
        );
    }

    private static void rejectSensitive(String value) {
        if (!SecretRedactor.redactFreeform(value).equals(value)
                || value.matches("(?is).*-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*")
                || value.matches("(?s).*\\bsk-[A-Za-z0-9_-]{16,}\\b.*")
                || value.matches("(?s).*\\bAKIA[0-9A-Z]{16}\\b.*")) {
            throw new IllegalArgumentException("state projection contains sensitive content");
        }
    }

    private static String stateJsonFromBlock(String block) {
        String suffix = "\n</rd-agent-state>";
        int start = block.indexOf('\n');
        if (!block.startsWith("<rd-agent-state ") || start < 0 || !block.endsWith(suffix)) {
            throw new IllegalArgumentException("injected block format is invalid");
        }
        return block.substring(start + 1, block.length() - suffix.length());
    }

    private static long epoch(JsonNode value, String field) {
        try {
            return Instant.parse(requiredText(value, field)).toEpochMilli();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " is not an ISO instant", exception);
        }
    }

    private static long nonNegative(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0L) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.asLong();
    }

    private static long positive(JsonNode value, String field) {
        long parsed = nonNegative(value, field);
        if (parsed < 1L) throw new IllegalArgumentException(field + " must be positive");
        return parsed;
    }

    private static String requiredHash(JsonNode value, String field) {
        String normalized = requiredText(value, field).toLowerCase();
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hash");
        }
        return normalized;
    }

    private static String requiredText(JsonNode value, String field) {
        String normalized = value == null ? "" : value.asText("").strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static void requireEquals(String expected, String actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(label + " mismatch");
        }
    }

    private void warn(String message) {
        try {
            warningSink.accept(message);
        } catch (RuntimeException ignored) {
            LOGGER.warn("Agent state projection warning sink failed: {}", message);
        }
    }

    private static String safeError(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return SecretRedactor.redactFreeform(message == null ? error.getClass().getSimpleName() : message);
    }

    @FunctionalInterface
    private interface ProjectionCommand {
        AgentStageProjectionWriteResult project(AgentStageStateProjectionStore store);
    }
}
