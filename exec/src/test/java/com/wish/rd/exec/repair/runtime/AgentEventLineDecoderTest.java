package com.wish.rd.exec.repair.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentEventLineDecoderTest {

    @Test
    void decodesChunkedLfJsonAndKeepsSequenceOrder() {
        List<JsonNode> events = new ArrayList<>();
        AgentEventLineDecoder decoder = new AgentEventLineDecoder(new ObjectMapper(), events::add);
        decoder.accept("{\"protocol\":\"rd-agent-event/v1\",\"eventType\":\"RUNTIME_READY\",\"sourceSequence\":1}\n{\"protocol\":");
        decoder.accept("\"rd-agent-event/v1\",\"eventType\":\"AGENT_SETTLED\",\"sourceSequence\":2}\n");

        assertEquals(2, events.size());
        assertEquals(2L, decoder.lastSourceSequence());
    }

    @Test
    void rejectsMalformedProtocolAndDuplicateSequence() {
        AgentEventLineDecoder decoder = new AgentEventLineDecoder(new ObjectMapper(), ignored -> { });
        assertThrows(IllegalArgumentException.class, () -> decoder.accept("not-json\n"));
        decoder.accept("{\"protocol\":\"rd-agent-event/v1\",\"eventType\":\"RUNTIME_READY\",\"sourceSequence\":1}\n");
        assertThrows(IllegalArgumentException.class, () -> decoder.accept(
                "{\"protocol\":\"rd-agent-event/v1\",\"eventType\":\"AGENT_SETTLED\",\"sourceSequence\":1}\n"
        ));
    }
}
