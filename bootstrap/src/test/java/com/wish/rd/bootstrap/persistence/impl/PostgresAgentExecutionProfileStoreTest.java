package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.AgentExecutionProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentExecutionProfileMapper;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAgentExecutionProfileStoreTest {

    @Test
    void insertsCapabilitiesAsCanonicalJson() {
        AgentExecutionProfileMapper mapper = mock(AgentExecutionProfileMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        PostgresAgentExecutionProfileStore store = new PostgresAgentExecutionProfileStore(mapper);

        AgentExecutionProfile inserted = store.insert(profile(1L));

        assertEquals(List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2), inserted.capabilities());
        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat(row ->
                "[\"PI_AGENT_STATE_V2\"]".equals(row.capabilitiesJson)
                        && Long.valueOf(1L).equals(row.version)));
    }

    @Test
    void returnsEmptyWhenExpectedVersionUpdateLosesTheCas() {
        AgentExecutionProfileMapper mapper = mock(AgentExecutionProfileMapper.class);
        when(mapper.update(any(), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(0);
        PostgresAgentExecutionProfileStore store = new PostgresAgentExecutionProfileStore(mapper);

        assertTrue(store.update(profile(2L), 1L).isEmpty());
    }

    @Test
    void decodesLegacyNullCapabilitiesAsEmpty() {
        AgentExecutionProfileMapper mapper = mock(AgentExecutionProfileMapper.class);
        AgentExecutionProfileRow row = row();
        row.capabilitiesJson = null;
        when(mapper.find("pi-coding")).thenReturn(row);
        PostgresAgentExecutionProfileStore store = new PostgresAgentExecutionProfileStore(mapper);

        assertEquals(List.of(), store.find("pi-coding").orElseThrow().capabilities());
    }

    private static AgentExecutionProfile profile(long version) {
        return new AgentExecutionProfile(
                "pi-coding", "1", "CODING_AGENT", "Pi coding", AgentRuntimeType.PI,
                "provider", "", "", 0L, "coding-tools", 1L, true, version,
                List.of(AgentRuntimeCapability.PI_AGENT_STATE_V2)
        );
    }

    private static AgentExecutionProfileRow row() {
        AgentExecutionProfileRow row = new AgentExecutionProfileRow();
        row.profileId = "pi-coding";
        row.projectId = 1L;
        row.role = "CODING_AGENT";
        row.name = "Pi coding";
        row.runtimeType = "PI";
        row.providerProfileId = "provider";
        row.modelOverride = "";
        row.extensionSetId = "";
        row.extensionSetVersion = 0L;
        row.toolPolicyId = "coding-tools";
        row.toolPolicyVersion = 1L;
        row.enabled = true;
        row.version = 1L;
        return row;
    }
}
