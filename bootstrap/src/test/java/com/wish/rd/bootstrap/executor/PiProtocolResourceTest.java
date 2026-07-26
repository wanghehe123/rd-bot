package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProtocolResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ROOT = "executor/pi/protocol/";

    @Test
    void shouldShipVersionedRequestEventAndResultSchemas() throws IOException {
        for (String resource : List.of(
                "rd-pi-request-v1.schema.json",
                "rd-agent-event-v1.schema.json",
                "rd-result-v1.schema.json"
        )) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(ROOT + resource)) {
                assertNotNull(input, resource);
                JsonNode schema = OBJECT_MAPPER.readTree(input);
                assertEquals("http://json-schema.org/draft-07/schema#", schema.path("$schema").asText());
                assertTrue(schema.path("$id").asText().contains(resource));
            }
        }
    }

    @Test
    void shouldRequireTheRuntimeEnvelopeAndSafeResultFields() throws IOException {
        JsonNode event = read("rd-agent-event-v1.schema.json");
        assertTrue(event.path("required").toString().contains("eventType"));
        assertTrue(event.path("required").toString().contains("sourceSequence"));
        assertEquals("rd-agent-event/v1", event.path("properties").path("protocol").path("const").asText());

        JsonNode result = read("rd-result-v1.schema.json");
        assertTrue(result.path("required").toString().contains("status"));
        assertTrue(result.path("required").toString().contains("summary"));
        assertTrue(result.path("properties").path("status").path("enum").toString().contains("SUCCESS"));
    }

    private JsonNode read(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
