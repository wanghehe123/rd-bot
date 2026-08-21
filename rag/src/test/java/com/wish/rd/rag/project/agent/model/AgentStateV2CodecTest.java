package com.wish.rd.rag.project.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateV2CodecTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void matchesSharedCanonicalAndHashFixtures() throws Exception {
        JsonNode fixtures = fixtures();
        for (JsonNode fixture : fixtures.path("valid")) {
            JsonNode input = fixture.path("input");
            assertEquals(fixture.path("canonical").textValue(), AgentStateV2Codec.canonicalize(input));
            assertEquals(fixture.path("hash").textValue(), AgentStateV2Codec.hash(input));
            assertEquals(
                    input,
                    AgentStateV2Codec.decodeAndVerify(
                            fixture.path("canonical").textValue(),
                            fixture.path("hash").textValue()
                    )
            );
        }
    }

    @Test
    void rejectsSharedInvalidFixtures() throws Exception {
        for (JsonNode fixture : fixtures().path("invalid")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentStateV2Codec.decodeAndVerify(
                            fixture.path("json").textValue(),
                            fixture.path("hash").textValue()
                    ),
                    fixture.path("name").textValue()
            );
            assertTrue(
                    error.getMessage().toLowerCase().contains(fixture.path("error").textValue()),
                    () -> fixture.path("name").textValue() + ": " + error.getMessage()
            );
        }
    }

    private static JsonNode fixtures() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fixture = root.resolve("test-fixtures/protocol/rd-agent-state-v2-canonical-fixtures.json");
        if (!Files.exists(fixture)) {
            fixture = root.getParent().resolve(
                    "test-fixtures/protocol/rd-agent-state-v2-canonical-fixtures.json"
            );
        }
        return OBJECT_MAPPER.readTree(Files.readString(fixture));
    }
}
