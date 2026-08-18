package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentToolPolicyRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentToolPolicyMapper;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresAgentToolPolicyStoreTest {

    private static final String SEED_JSON = """
            {"hostAllow":["bash","edit","rd_record_fact","rd_submit_result","rd_todo_rewrite","rd_todo_update_status","read","write"],"allow":["bash","edit","rd_record_fact","rd_submit_result","rd_todo_rewrite","rd_todo_update_status","read","write"],"deny":[]}""";

    @Test
    void shouldRepairSeedPlaceholderHashInsteadOfFailingResolution() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = row("legacy-host-bound", 1L, SEED_JSON, "legacy-host-bound-v1");
        PostgresAgentToolPolicyStore store = new PostgresAgentToolPolicyStore(mapper, new ObjectMapper());

        AgentToolPolicy policy = store.find("legacy-host-bound", 1L).orElseThrow();

        assertEquals("legacy-host-bound", policy.policyId());
        assertEquals(1L, policy.version());
        assertTrue(policy.allow().contains("rd_submit_result"));
        assertEquals(PostgresPersistenceSupport.checksum(SEED_JSON), mapper.row.policyHash);
        assertEquals(1, mapper.upserts);
    }

    @Test
    void shouldFailClosedWhenSha256HashDoesNotMatchJson() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.row = row(
                "legacy-host-bound",
                1L,
                SEED_JSON,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        PostgresAgentToolPolicyStore store = new PostgresAgentToolPolicyStore(mapper, new ObjectMapper());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> store.find("legacy-host-bound", 1L)
        );
        assertTrue(exception.getMessage().contains("agent tool policy hash mismatch"));
        assertEquals(0, mapper.upserts);
    }

    private static AgentToolPolicyRow row(String id, long version, String json, String hash) {
        AgentToolPolicyRow row = new AgentToolPolicyRow();
        row.policyId = id;
        row.version = version;
        row.policyJson = json;
        row.policyHash = hash;
        row.enabled = true;
        return row;
    }

    private static final class RecordingMapper implements AgentToolPolicyMapper {
        private AgentToolPolicyRow row;
        private int upserts;

        @Override
        public int upsert(AgentToolPolicyRow next) {
            upserts += 1;
            row = next;
            return 1;
        }

        @Override
        public AgentToolPolicyRow find(String policyId, long version) {
            return row;
        }

        @Override
        public AgentToolPolicyRow findLatest(String policyId) {
            return row;
        }
    }
}
