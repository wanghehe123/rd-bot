package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                List.of("npm test")
        );

        controller.updateProject("100", request);
        controller.updateTask("200", request);

        assertEquals("PROJECT", controller.getProject("100").scopeType());
        assertEquals("TASK", controller.getTask("200").scopeType());
        assertEquals("http://127.0.0.1:5173", controller.getTask("200").baseUrl());
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
