package com.wish.rd.bootstrap.controller.admin.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaValidationProfileControllerTest {

    @Test
    void shouldManageProjectAndTaskOverrides() {
        QaValidationProfileController controller = new QaValidationProfileController(
                new QaValidationProfileService(new InMemoryStore())
        );
        QaValidationProfileController.ProfileRequest request = new QaValidationProfileController.ProfileRequest(
                "REQUIRED",
                "http://127.0.0.1:5173",
                "npm run dev -- --host 0.0.0.0",
                "/health",
                List.of("127.0.0.1", "localhost"),
                List.of("npm test"),
                null,
                null
        );

        controller.updateProject("100", request);
        controller.updateTask("200", request);

        assertEquals("PROJECT", controller.getProject("100").scopeType());
        assertEquals("TASK", controller.getTask("200").scopeType());
        assertEquals("http://127.0.0.1:5173", controller.getTask("200").baseUrl());
        assertFalse(controller.getProject("100").buildCommandsDeclared());
    }

    @Test
    void omittedJsonCommandListsStayUndeclared() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QaValidationProfileController.ProfileRequest request = mapper.readValue(
                """
                        {"mode":"AUTO"}
                        """,
                QaValidationProfileController.ProfileRequest.class
        );
        assertNull(request.buildCommands());
        assertNull(request.staticCommands());

        QaValidationProfileController controller = new QaValidationProfileController(
                new QaValidationProfileService(new InMemoryStore())
        );
        QaValidationProfile saved = controller.updateProject("100", request);
        assertFalse(saved.buildCommandsDeclared());
        assertFalse(saved.staticCommandsDeclared());
        JsonNode json = mapper.valueToTree(controller.getProject("100"));
        assertTrue(json.get("buildCommands").isNull());
        assertTrue(json.get("staticCommands").isNull());
    }

    @Test
    void emptyJsonCommandListsMeanExplicitSkip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QaValidationProfileController.ProfileRequest request = mapper.readValue(
                """
                        {"mode":"AUTO","buildCommands":[],"staticCommands":[]}
                        """,
                QaValidationProfileController.ProfileRequest.class
        );
        assertEquals(List.of(), request.buildCommands());
        assertEquals(List.of(), request.staticCommands());

        QaValidationProfileController controller = new QaValidationProfileController(
                new QaValidationProfileService(new InMemoryStore())
        );
        QaValidationProfile saved = controller.updateProject("100", request);
        assertTrue(saved.buildCommandsDeclared());
        assertTrue(saved.buildCommands().isEmpty());
        JsonNode json = mapper.valueToTree(saved);
        assertTrue(json.get("buildCommands").isArray());
        assertEquals(0, json.get("buildCommands").size());
    }

    private static final class InMemoryStore implements QaValidationProfileStore {
        private final Map<String, QaValidationProfile> data = new HashMap<>();

        @Override
        public QaValidationProfile save(QaValidationProfile profile) {
            data.put(profile.scopeType() + ":" + profile.scopeId(), profile);
            return profile;
        }

        @Override
        public Optional<QaValidationProfile> find(String scopeType, String scopeId) {
            return Optional.ofNullable(data.get(scopeType + ":" + scopeId));
        }
    }
}
