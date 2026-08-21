package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.AgentStageStateProjectionRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentStageStateProjectionMapper;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStageProjectionWriteStatus;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAgentStageStateProjectionStoreTest {

    private static final String HASH_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final AgentStageStateIdentity IDENTITY = new AgentStageStateIdentity(
            "1001", "2001", "CODING_AGENT", 1
    );

    @Test
    void migrationDefinesIndependentCasFieldsAndDoesNotPersistStale() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/sql/postgres/p18_pi_agent_state_and_remediation.sql"
        ));
        String normalized = sql.replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ADD COLUMN IF NOT EXISTS capabilities_json JSONB NOT NULL DEFAULT '[]'::jsonb"));
        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS rd_agent_stage_state_latest"));
        assertTrue(normalized.contains("stage_run_id BIGINT PRIMARY KEY"));
        assertTrue(normalized.contains("REFERENCES rd_agent_stage_runs(id) ON DELETE CASCADE"));
        assertTrue(normalized.contains("state_sequence BIGINT NOT NULL DEFAULT -1"));
        assertTrue(normalized.contains("injection_sequence BIGINT NOT NULL DEFAULT 0"));
        assertTrue(normalized.contains("injected_state_sequence BIGINT NOT NULL DEFAULT -1"));
        assertTrue(normalized.contains("CHECK (state_sequence >= -1)"));
        assertTrue(normalized.contains("CHECK (injection_sequence >= 0)"));
        assertFalse(sql.matches("(?s).*\\bstale\\b.*"), "staleness must be computed by Host, not persisted");
    }

    @Test
    void insertsStateAndUsesMapperCasForLargerSequence() {
        AgentStageStateProjectionMapper mapper = mock(AgentStageStateProjectionMapper.class);
        when(mapper.insertState(any())).thenReturn(1, 0);
        PostgresAgentStageStateProjectionStore store = new PostgresAgentStageStateProjectionStore(mapper);

        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectState(
                new AgentStateProjectionUpdate(IDENTITY, 0L, HASH_A, "{\"sequence\":0}", 100L)
        ).status());
        verify(mapper).insertState(org.mockito.ArgumentMatchers.argThat(row ->
                row.stageRunId == 2001L && row.taskId == 1001L && row.stateSequence == 0L
                        && HASH_A.equals(row.stateHash)));

        AgentStageStateProjectionRow current = row();
        current.stateSequence = 0L;
        current.stateHash = HASH_A;
        current.stateJson = "{\"sequence\":0}";
        when(mapper.find(2001L)).thenReturn(current);
        when(mapper.updateState(any())).thenReturn(1);
        assertEquals(AgentStageProjectionWriteStatus.APPLIED, store.projectState(
                new AgentStateProjectionUpdate(IDENTITY, 2L, HASH_B, "{\"sequence\":2}", 200L)
        ).status());
        verify(mapper).updateState(org.mockito.ArgumentMatchers.argThat(row -> row.stateSequence == 2L));
    }

    @Test
    void classifiesInjectionReplayConflictAndRegressionWithoutWriting() {
        AgentStageStateProjectionMapper mapper = mock(AgentStageStateProjectionMapper.class);
        AgentStageStateProjectionRow current = row();
        current.injectionSequence = 3L;
        current.injectedStateSequence = 2L;
        current.injectedStateHash = HASH_A;
        current.promptHash = HASH_B;
        current.injectedBlockHash = HASH_A;
        current.injectedBlock = "safe-block";
        current.injectionIdempotencyKey = HASH_B;
        when(mapper.find(2001L)).thenReturn(current);
        PostgresAgentStageStateProjectionStore store = new PostgresAgentStageStateProjectionStore(mapper);

        assertEquals(AgentStageProjectionWriteStatus.IDEMPOTENT, store.projectInjection(
                injection(3L, HASH_A)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_CONFLICT, store.projectInjection(
                injection(3L, HASH_B)
        ).status());
        assertEquals(AgentStageProjectionWriteStatus.REJECTED_STALE, store.projectInjection(
                injection(2L, HASH_A)
        ).status());
    }

    private static AgentContextInjectionProjectionUpdate injection(long sequence, String blockHash) {
        return new AgentContextInjectionProjectionUpdate(
                IDENTITY, sequence, 2L, HASH_A, HASH_B, blockHash,
                "safe-block", HASH_B, 300L
        );
    }

    private static AgentStageStateProjectionRow row() {
        AgentStageStateProjectionRow row = new AgentStageStateProjectionRow();
        row.stageRunId = 2001L;
        row.taskId = 1001L;
        row.role = "CODING_AGENT";
        row.attemptNo = 1;
        row.stateSequence = -1L;
        row.stateHash = "";
        row.stateJson = "{}";
        row.stateProjectedAt = null;
        row.injectionSequence = 0L;
        row.injectedStateSequence = -1L;
        row.injectedStateHash = "";
        row.promptHash = "";
        row.injectedBlockHash = "";
        row.injectedBlock = "";
        row.injectionIdempotencyKey = "";
        row.injectedAt = null;
        row.finalized = false;
        row.finalizedAt = null;
        row.updatedAt = OffsetDateTime.now();
        return row;
    }
}
