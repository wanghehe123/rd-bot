package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentStageStateProjectionRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentStageStateProjectionMapper;
import com.wish.rd.rag.project.agent.AgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteResult;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteStatus;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStageStateProjection;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** PostgreSQL CAS adapter for live Agent state and actual context injections. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentStageStateProjectionStore implements AgentStageStateProjectionStore {

    private final AgentStageStateProjectionMapper mapper;

    public PostgresAgentStageStateProjectionStore(AgentStageStateProjectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentStageProjectionWriteResult projectState(AgentStateProjectionUpdate update) {
        AgentStageStateProjectionRow proposed = stateRow(update);
        if (mapper.insertState(proposed) == 1) {
            return accepted(AgentStageProjectionWriteStatus.APPLIED,
                    AgentStageStateProjection.empty(update.identity()).withState(update));
        }
        AgentStageStateProjection current = requireCurrent(update.identity().stageRunId());
        AgentStageProjectionWriteResult terminal = classifyState(current, update);
        if (terminal != null) return terminal;
        if (mapper.updateState(proposed) == 1) {
            return accepted(AgentStageProjectionWriteStatus.APPLIED, current.withState(update));
        }
        AgentStageStateProjection raced = requireCurrent(update.identity().stageRunId());
        AgentStageProjectionWriteResult racedResult = classifyState(raced, update);
        return racedResult == null
                ? rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, raced, "state CAS lost")
                : racedResult;
    }

    @Override
    public AgentStageProjectionWriteResult projectInjection(AgentContextInjectionProjectionUpdate update) {
        AgentStageStateProjectionRow proposed = injectionRow(update);
        if (mapper.insertInjection(proposed) == 1) {
            return accepted(AgentStageProjectionWriteStatus.APPLIED,
                    AgentStageStateProjection.empty(update.identity()).withInjection(update));
        }
        AgentStageStateProjection current = requireCurrent(update.identity().stageRunId());
        AgentStageProjectionWriteResult terminal = classifyInjection(current, update);
        if (terminal != null) return terminal;
        if (mapper.updateInjection(proposed) == 1) {
            return accepted(AgentStageProjectionWriteStatus.APPLIED, current.withInjection(update));
        }
        AgentStageStateProjection raced = requireCurrent(update.identity().stageRunId());
        AgentStageProjectionWriteResult racedResult = classifyInjection(raced, update);
        return racedResult == null
                ? rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, raced, "injection CAS lost")
                : racedResult;
    }

    @Override
    public AgentStageProjectionWriteResult finalizeProjection(
            AgentStageStateIdentity identity,
            long finalizedAtEpochMillis
    ) {
        AgentStageStateProjection current = requireCurrent(identity.stageRunId());
        if (!current.identity().equals(identity)) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, current, "identity conflict");
        }
        if (current.finalized()) {
            return accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, current);
        }
        AgentStageStateProjectionRow row = identityRow(identity);
        row.finalizedAt = time(finalizedAtEpochMillis);
        if (mapper.finalizeProjection(row) == 1) {
            return accepted(AgentStageProjectionWriteStatus.APPLIED, current.finalizeAt(finalizedAtEpochMillis));
        }
        AgentStageStateProjection raced = requireCurrent(identity.stageRunId());
        return raced.finalized()
                ? accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, raced)
                : rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, raced, "finalization CAS lost");
    }

    @Override
    public Optional<AgentStageStateProjection> findByStageRunId(String stageRunId) {
        if (stageRunId == null || stageRunId.isBlank()) return Optional.empty();
        return Optional.ofNullable(mapper.find(PostgresPersistenceSupport.parseId(stageRunId))).map(this::toModel);
    }

    private AgentStageProjectionWriteResult classifyState(
            AgentStageStateProjection current,
            AgentStateProjectionUpdate update
    ) {
        if (!current.identity().equals(update.identity())) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, current, "identity conflict");
        }
        if (current.stateSequence() == update.stateSequence()) {
            return current.stateHash().equals(update.stateHash()) && current.stateJson().equals(update.stateJson())
                    ? accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, current)
                    : rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, current,
                    "same state sequence has conflicting content");
        }
        if (current.finalized()) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_FINALIZED, current, "projection is finalized");
        }
        if (update.stateSequence() < current.stateSequence()) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_STALE, current, "state sequence regressed");
        }
        return null;
    }

    private AgentStageProjectionWriteResult classifyInjection(
            AgentStageStateProjection current,
            AgentContextInjectionProjectionUpdate update
    ) {
        if (!current.identity().equals(update.identity())) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_IDENTITY, current, "identity conflict");
        }
        if (current.injectionSequence() == update.injectionSequence()) {
            boolean same = current.injectedStateSequence() == update.stateSequence()
                    && current.injectedStateHash().equals(update.stateHash())
                    && current.promptHash().equals(update.promptHash())
                    && current.injectedBlockHash().equals(update.injectedBlockHash())
                    && current.injectedBlock().equals(update.injectedBlock())
                    && current.injectionIdempotencyKey().equals(update.idempotencyKey());
            return same
                    ? accepted(AgentStageProjectionWriteStatus.IDEMPOTENT, current)
                    : rejected(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, current,
                    "same injection sequence has conflicting content");
        }
        if (current.finalized()) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_FINALIZED, current, "projection is finalized");
        }
        if (update.injectionSequence() < current.injectionSequence()) {
            return rejected(AgentStageProjectionWriteStatus.REJECTED_STALE, current, "injection sequence regressed");
        }
        return null;
    }

    private AgentStageStateProjection requireCurrent(String stageRunId) {
        return findByStageRunId(stageRunId)
                .orElseThrow(() -> new IllegalStateException("projection row disappeared: " + stageRunId));
    }

    private static AgentStageStateProjectionRow stateRow(AgentStateProjectionUpdate update) {
        AgentStageStateProjectionRow row = identityRow(update.identity());
        row.stateSequence = update.stateSequence();
        row.stateHash = update.stateHash();
        row.stateJson = update.stateJson();
        row.stateProjectedAt = time(update.projectedAtEpochMillis());
        return row;
    }

    private static AgentStageStateProjectionRow injectionRow(AgentContextInjectionProjectionUpdate update) {
        AgentStageStateProjectionRow row = identityRow(update.identity());
        row.injectionSequence = update.injectionSequence();
        row.injectedStateSequence = update.stateSequence();
        row.injectedStateHash = update.stateHash();
        row.promptHash = update.promptHash();
        row.injectedBlockHash = update.injectedBlockHash();
        row.injectedBlock = update.injectedBlock();
        row.injectionIdempotencyKey = update.idempotencyKey();
        row.injectedAt = time(update.injectedAtEpochMillis());
        return row;
    }

    private static AgentStageStateProjectionRow identityRow(AgentStageStateIdentity identity) {
        AgentStageStateProjectionRow row = new AgentStageStateProjectionRow();
        row.stageRunId = PostgresPersistenceSupport.parseId(identity.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(identity.taskId());
        row.role = identity.role();
        row.attemptNo = identity.attemptNo();
        row.stateSequence = -1L;
        row.stateHash = "";
        row.stateJson = "{}";
        row.injectionSequence = 0L;
        row.injectedStateSequence = -1L;
        row.injectedStateHash = "";
        row.promptHash = "";
        row.injectedBlockHash = "";
        row.injectedBlock = "";
        row.injectionIdempotencyKey = "";
        return row;
    }

    private AgentStageStateProjection toModel(AgentStageStateProjectionRow row) {
        return new AgentStageStateProjection(
                new AgentStageStateIdentity(
                        PostgresPersistenceSupport.idString(row.taskId),
                        PostgresPersistenceSupport.idString(row.stageRunId),
                        row.role,
                        row.attemptNo
                ),
                row.stateSequence, safe(row.stateHash), safe(row.stateJson), epoch(row.stateProjectedAt),
                row.injectionSequence, row.injectedStateSequence, safe(row.injectedStateHash),
                safe(row.promptHash), safe(row.injectedBlockHash), safe(row.injectedBlock),
                safe(row.injectionIdempotencyKey), epoch(row.injectedAt),
                row.finalized, epoch(row.finalizedAt)
        );
    }

    private static OffsetDateTime time(long epochMillis) {
        if (epochMillis < 1) throw new IllegalArgumentException("epoch millis must be positive");
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private static long epoch(OffsetDateTime value) {
        return value == null ? 0L : value.toInstant().toEpochMilli();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static AgentStageProjectionWriteResult accepted(
            AgentStageProjectionWriteStatus status,
            AgentStageStateProjection projection
    ) {
        return new AgentStageProjectionWriteResult(status, projection, "");
    }

    private static AgentStageProjectionWriteResult rejected(
            AgentStageProjectionWriteStatus status,
            AgentStageStateProjection projection,
            String reason
    ) {
        return new AgentStageProjectionWriteResult(status, projection, reason);
    }
}
