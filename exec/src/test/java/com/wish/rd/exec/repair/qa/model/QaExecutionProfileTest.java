package com.wish.rd.exec.repair.qa.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaExecutionProfileTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void undeclaredCommandListsDoNotSerializeAsEmptySkipArrays() throws Exception {
        QaExecutionProfile profile = undeclared();

        assertFalse(profile.buildCommandsDeclared());
        assertTrue(profile.buildCommands().isEmpty());
        assertFalse(profile.staticCommandsDeclared());
        assertTrue(profile.staticCommands().isEmpty());

        String json = OBJECT_MAPPER.writeValueAsString(profile);
        JsonNode tree = OBJECT_MAPPER.valueToTree(profile);

        assertFalse(json.contains("\"buildCommands\":[]"), json);
        assertFalse(json.contains("\"staticCommands\":[]"), json);
        assertTrue(tree.path("buildCommands").isMissingNode() || tree.path("buildCommands").isNull(), tree.toString());
        assertTrue(tree.path("staticCommands").isMissingNode() || tree.path("staticCommands").isNull(), tree.toString());
    }

    @Test
    void explicitEmptyCommandListsSerializeAsSkipArrays() throws Exception {
        QaExecutionProfile profile = new QaExecutionProfile(
                false,
                false,
                "TASK_OVERRIDE",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                true,
                "explicit skip"
        );

        assertTrue(profile.buildCommandsDeclared());
        assertTrue(profile.buildCommands().isEmpty());
        assertTrue(profile.staticCommandsDeclared());
        assertTrue(profile.staticCommands().isEmpty());

        String json = OBJECT_MAPPER.writeValueAsString(profile);
        JsonNode tree = OBJECT_MAPPER.valueToTree(profile);

        assertTrue(json.contains("\"buildCommands\":[]"), json);
        assertTrue(json.contains("\"staticCommands\":[]"), json);
        assertTrue(tree.get("buildCommands").isArray());
        assertEquals(0, tree.get("buildCommands").size());
        assertTrue(tree.get("staticCommands").isArray());
        assertEquals(0, tree.get("staticCommands").size());
    }

    private static QaExecutionProfile undeclared() {
        return new QaExecutionProfile(
                true,
                false,
                "AUTO_DETECTION",
                "http://127.0.0.1:5173",
                "npm run dev -- --host 0.0.0.0",
                "/",
                List.of("127.0.0.1"),
                List.of(),
                "Vite project"
        );
    }
}
