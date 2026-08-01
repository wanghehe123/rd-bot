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
                "rd-pi-request-v2.schema.json",
                "rd-role-execution-input-manifest-v1.schema.json",
                "rd-runtime-context-policy-v1.schema.json",
                "rd-runtime-context-manifest-v1.schema.json",
                "rd-agent-event-v1.schema.json",
                "rd-result-v1.schema.json",
                "rd-agent-fact-v1.schema.json",
                "rd-agent-state-v1.schema.json",
                "rd-agent-state-action-v1.schema.json"
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
        assertTrue(result.path("properties").has("facts"));
        assertEquals("rd-agent-fact-v1.schema.json",
                result.path("properties").path("facts").path("items").path("$ref").asText());
    }

    @Test
    void shouldShipFactAndStateSchemas() throws IOException {
        JsonNode fact = read("rd-agent-fact-v1.schema.json");
        assertTrue(fact.path("required").toString().contains("kind"));
        assertTrue(fact.path("properties").path("kind").path("enum").toString().contains("OBSERVED"));

        JsonNode state = read("rd-agent-state-v1.schema.json");
        assertEquals("rd-agent-state/v1", state.path("properties").path("protocol").path("const").asText());

        JsonNode action = read("rd-agent-state-action-v1.schema.json");
        assertTrue(action.path("properties").path("decision").path("enum").toString().contains("ACCEPTED"));
    }

    @Test
    void shouldRequireRequestV2ManifestPathAndContextPolicy() throws IOException {
        JsonNode request = read("rd-pi-request-v2.schema.json");
        assertEquals("rd-pi-request/v2", request.path("properties").path("protocol").path("const").asText());
        assertTrue(request.path("required").toString().contains("inputManifestPath"));
        assertTrue(request.path("required").toString().contains("contextPolicy"));
        assertTrue(request.path("required").toString().contains("inputManifestHash"));
        assertEquals(
                "/work/input/role-execution-input-manifest.json",
                request.path("properties").path("inputManifestPath").path("const").asText()
        );

        JsonNode policy = read("rd-runtime-context-policy-v1.schema.json");
        assertEquals(
                "rd-runtime-context-policy/v1",
                policy.path("properties").path("protocol").path("const").asText()
        );
        assertTrue(policy.path("properties").path("mode").path("enum").toString().contains("ROOT_ONLY"));
        assertTrue(policy.path("properties").path("mode").path("enum").toString().contains("LEGACY_OBSERVE_ONLY"));

        JsonNode inputManifest = read("rd-role-execution-input-manifest-v1.schema.json");
        assertTrue(inputManifest.path("required").toString().contains("runtimeContextPolicy"));
        assertTrue(inputManifest.path("required").toString().contains("budget"));

        JsonNode runtimeManifest = read("rd-runtime-context-manifest-v1.schema.json");
        assertEquals(
                "rd-runtime-context-manifest/v1",
                runtimeManifest.path("properties").path("protocol").path("const").asText()
        );
    }

    private JsonNode read(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(ROOT + name)) {
            assertNotNull(input, name);
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
