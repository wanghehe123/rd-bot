package com.wish.rd.exec.repair.runtime.impl;

import com.wish.rd.exec.repair.runtime.AgentExecutionEventStore;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionEvent;
import com.wish.rd.exec.repair.runtime.model.AgentExecutionTraceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bounded local event store used by the memory profile and tests.
 *
 * <p>The store assigns an authoritative per-stage sequence, suppresses repeated
 * bridge source sequences, and isolates browser subscriber failures from the
 * container output-draining thread.</p>
 */
public final class InMemoryAgentExecutionEventStore implements AgentExecutionEventStore {

    public static final int DEFAULT_MAX_EVENTS_PER_STAGE = 512;
    private static final String PROTOCOL = "rd-agent-event/v1";

    private final int maxEventsPerStage;
    private final ConcurrentMap<StageKey, StageBuffer> buffers = new ConcurrentHashMap<>();

    public InMemoryAgentExecutionEventStore() {
        this(DEFAULT_MAX_EVENTS_PER_STAGE);
    }

    public InMemoryAgentExecutionEventStore(int maxEventsPerStage) {
        if (maxEventsPerStage <= 0) {
            throw new IllegalArgumentException("maxEventsPerStage must be positive");
        }
        this.maxEventsPerStage = maxEventsPerStage;
    }

    @Override
    public void onEvent(String containerName, JsonNode event) {
        if (event == null || !event.isObject()) {
            throw new IllegalArgumentException("event must be a JSON object");
        }
        if (!PROTOCOL.equals(event.path("protocol").asText(""))) {
            throw new IllegalArgumentException("event protocol is invalid");
        }
        StageKey key = StageKey.of(event.path("taskId").asText(""), event.path("stageRunId").asText(""));
        long sourceSequence = event.path("sourceSequence").asLong(-1L);
        if (key.taskId().isBlank() || key.stageRunId().isBlank() || sourceSequence <= 0L) {
            throw new IllegalArgumentException("event identity and sourceSequence are required");
        }
        String eventType = event.path("eventType").asText("").strip();
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        StageBuffer buffer = buffers.computeIfAbsent(key, ignored -> new StageBuffer(maxEventsPerStage));
        AgentExecutionEvent accepted = buffer.append(containerName, sourceSequence, event);
        if (accepted == null) {
            return;
        }
        for (Subscription subscription : buffer.subscriptions) {
            try {
                subscription.listener().accept(accepted);
            } catch (RuntimeException ignored) {
                // A disconnected browser must never stop stdout draining or the Pi attempt.
            }
        }
    }

    @Override
    public AgentExecutionTraceSnapshot snapshot(String taskId, String stageRunId, long afterSequence, int limit) {
        StageKey key = StageKey.of(taskId, stageRunId);
        if (key.taskId().isBlank() || key.stageRunId().isBlank()) {
            return AgentExecutionTraceSnapshot.unavailable("LIVE");
        }
        StageBuffer buffer = buffers.get(key);
        return buffer == null
                ? AgentExecutionTraceSnapshot.unavailable("LIVE")
                : buffer.snapshot(afterSequence, limit);
    }

    @Override
    public AutoCloseable subscribe(String taskId, String stageRunId, Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        StageKey key = StageKey.of(taskId, stageRunId);
        if (key.taskId().isBlank() || key.stageRunId().isBlank()) {
            return () -> {
            };
        }
        StageBuffer buffer = buffers.computeIfAbsent(key, ignored -> new StageBuffer(maxEventsPerStage));
        Subscription subscription = new Subscription(listener);
        buffer.subscriptions.add(subscription);
        return () -> buffer.subscriptions.remove(subscription);
    }

    private record StageKey(String taskId, String stageRunId) {
        private static StageKey of(String taskId, String stageRunId) {
            return new StageKey(normalize(taskId), normalize(stageRunId));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }

    private record Subscription(Consumer<AgentExecutionEvent> listener) {
    }

    private static final class StageBuffer {
        private final int maxEvents;
        private final ArrayDeque<AgentExecutionEvent> events = new ArrayDeque<>();
        private final Map<Long, Long> authoritativeSequenceBySource = new LinkedHashMap<>();
        private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private long nextAuthoritativeSequence;
        private boolean truncated;

        private StageBuffer(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        private synchronized AgentExecutionEvent append(String containerName, long sourceSequence, JsonNode rawEvent) {
            if (authoritativeSequenceBySource.containsKey(sourceSequence)) {
                return null;
            }
            long sequence = ++nextAuthoritativeSequence;
            JsonNode event = rawEvent.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) event).put("sequence", sequence);
            AgentExecutionEvent accepted = new AgentExecutionEvent(sequence, containerName, event);
            events.addLast(accepted);
            authoritativeSequenceBySource.put(sourceSequence, sequence);
            while (events.size() > maxEvents) {
                AgentExecutionEvent removed = events.removeFirst();
                truncated = true;
                authoritativeSequenceBySource.remove(removed.event().path("sourceSequence").asLong(-1L));
            }
            while (authoritativeSequenceBySource.size() > maxEvents * 4) {
                Long first = authoritativeSequenceBySource.keySet().iterator().next();
                authoritativeSequenceBySource.remove(first);
            }
            return accepted;
        }

        private synchronized AgentExecutionTraceSnapshot snapshot(long afterSequence, int requestedLimit) {
            long cursor = Math.max(0L, afterSequence);
            int limit = Math.max(1, Math.min(200, requestedLimit <= 0 ? 100 : requestedLimit));
            List<AgentExecutionEvent> retained = new ArrayList<>(events);
            long oldest = retained.isEmpty() ? 0L : retained.getFirst().sequence();
            boolean cursorTruncated = truncated && cursor < Math.max(0L, oldest - 1L);
            List<JsonNode> selected = retained.stream()
                    .filter(item -> item.sequence() > cursor)
                    .limit(limit)
                    .map(AgentExecutionEvent::event)
                    .toList();
            long next = selected.isEmpty()
                    ? cursor
                    : selected.getLast().path("sequence").asLong(cursor);
            boolean hasMore = retained.stream().anyMatch(item -> item.sequence() > next);
            boolean finalized = retained.stream().anyMatch(item -> {
                String type = item.event().path("eventType").asText("");
                return "AGENT_SETTLED".equals(type) || "RUNTIME_STOPPED".equals(type);
            });
            return new AgentExecutionTraceSnapshot(
                    AgentExecutionTraceSnapshot.VERSION,
                    "LIVE",
                    !retained.isEmpty(),
                    finalized,
                    cursorTruncated,
                    hasMore,
                    next,
                    selected
            );
        }
    }
}
