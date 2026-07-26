package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileSnapshotRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExecutionProfileSnapshotMapper;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** PostgreSQL-backed append-only snapshot store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentExecutionProfileSnapshotStore implements AgentExecutionProfileSnapshotStore {

    private final AgentExecutionProfileSnapshotMapper mapper;

    public PostgresAgentExecutionProfileSnapshotStore(AgentExecutionProfileSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentExecutionProfileSnapshot saveIfAbsent(AgentExecutionProfileSnapshot snapshot) {
        mapper.insertIfAbsent(toRow(snapshot));
        return findByStageRunId(snapshot.stageRunId()).orElseThrow(() -> new IllegalStateException(
                "agent execution profile snapshot was not persisted: " + snapshot.stageRunId()
        ));
    }

    @Override
    public Optional<AgentExecutionProfileSnapshot> findByStageRunId(String stageRunId) {
        return Optional.ofNullable(mapper.findByStageRunId(
                PostgresPersistenceSupport.parseId(stageRunId)
        )).map(PostgresAgentExecutionProfileSnapshotStore::toSnapshot);
    }

    @Override
    public Optional<AgentExecutionProfileSnapshot> findBySnapshotId(String snapshotId) {
        return Optional.ofNullable(mapper.findBySnapshotId(snapshotId))
                .map(PostgresAgentExecutionProfileSnapshotStore::toSnapshot);
    }

    private static AgentExecutionProfileSnapshotRow toRow(AgentExecutionProfileSnapshot snapshot) {
        AgentExecutionProfileSnapshotRow row = new AgentExecutionProfileSnapshotRow();
        row.snapshotId = snapshot.snapshotId();
        row.stageRunId = PostgresPersistenceSupport.parseId(snapshot.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(snapshot.taskId());
        row.role = snapshot.role();
        row.attemptNo = snapshot.attemptNo();
        row.runtimeType = snapshot.runtimeType().name();
        row.snapshotJson = snapshot.snapshotJson();
        row.snapshotHash = snapshot.snapshotHash();
        row.resolvedAt = PostgresPersistenceSupport.toDateTime(snapshot.resolvedAtEpochMillis());
        return row;
    }

    private static AgentExecutionProfileSnapshot toSnapshot(AgentExecutionProfileSnapshotRow row) {
        return new AgentExecutionProfileSnapshot(
                row.snapshotId,
                PostgresPersistenceSupport.idString(row.stageRunId),
                PostgresPersistenceSupport.idString(row.taskId),
                row.role,
                row.attemptNo == null ? 1 : row.attemptNo,
                AgentRuntimeType.parse(row.runtimeType),
                row.snapshotJson,
                row.snapshotHash,
                PostgresPersistenceSupport.toEpochMillis(row.resolvedAt)
        );
    }
}
