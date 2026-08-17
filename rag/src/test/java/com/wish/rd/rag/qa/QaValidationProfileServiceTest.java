package com.wish.rd.rag.qa;

import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaValidationProfileServiceTest {

    @Test
    void shouldResolveTaskOverrideBeforeProjectProfile() {
        InMemoryStore store = new InMemoryStore();
        QaValidationProfileService service = new QaValidationProfileService(store);
        service.updateProject("100", command("http://project:3000"));
        service.updateTask("200", command("http://task:4000"));

        QaValidationProfileService.Resolution resolution = service.resolve("200", "100");

        assertEquals("TASK_OVERRIDE", resolution.source());
        assertTrue(resolution.profile().isPresent());
        assertEquals("http://task:4000", resolution.profile().orElseThrow().baseUrl());
    }

    @Test
    void shouldFallBackToProjectProfileWhenTaskOverrideIsAbsent() {
        InMemoryStore store = new InMemoryStore();
        QaValidationProfileService service = new QaValidationProfileService(store);
        service.updateProject("100", command("http://project:3000"));

        QaValidationProfileService.Resolution resolution = service.resolve("200", "100");

        assertEquals("PROJECT_PROFILE", resolution.source());
        assertEquals("http://project:3000", resolution.profile().orElseThrow().baseUrl());
    }

    @Test
    void shouldSupportAutoProfilesWithoutInventingBrowserRuntimeValues() {
        QaValidationProfileCommand command = new QaValidationProfileCommand(
                "AUTO", "", "", "", java.util.List.of(), java.util.List.of("npm test"), null, null);

        assertEquals("AUTO", command.mode());
        assertEquals("", command.baseUrl());
        assertEquals(java.util.List.of("npm test"), command.regressionCommands());
    }

    @Test
    void shouldRejectUnsafeUrlsAndCredentialLiteralsInQaCommands() {
        assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
                "REQUIRED", "ftp://example.test", "npm start", "/", java.util.List.of("example.test"),
                java.util.List.of("npm test"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
                "REQUIRED", "http://127.0.0.1:3000", "API_TOKEN=plain-secret npm start", "/",
                java.util.List.of("127.0.0.1"), java.util.List.of("npm test"), null, null));
    }

    @Test
    void rejectsDevServerAsBuildCommand() {
        assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(),
                List.of("npm run dev -- --host 0.0.0.0"),
                List.of()));
    }

    @Test
    void rejectsNextDevAsBuildCommand() {
        assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(),
                List.of("next dev"),
                List.of()));
    }

    @Test
    void rejectsViteHostAsBuildCommand() {
        assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(),
                List.of("npx vite --host 0.0.0.0"),
                List.of()));
    }

    @Test
    void rejectsCredentialLiteralsInBuildCommands() {
        assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(),
                List.of("API_TOKEN=plain-secret npm run build"),
                List.of()));
    }

    @Test
    void missingBuildCommandsMeansUndeclaredNotSkip() {
        QaValidationProfileCommand command = new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(), null, null);
        assertFalse(command.buildCommandsDeclared());
        assertTrue(command.buildCommands().isEmpty());
        assertFalse(command.staticCommandsDeclared());
    }

    @Test
    void emptyBuildCommandsMeansExplicitSkip() {
        QaValidationProfileCommand command = new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(), List.of(), List.of());
        assertTrue(command.buildCommandsDeclared());
        assertTrue(command.buildCommands().isEmpty());
    }

    @Test
    void shouldPersistDeclaredBuildAndStaticCommandsOnUpdate() {
        InMemoryStore store = new InMemoryStore();
        QaValidationProfileService service = new QaValidationProfileService(store);

        QaValidationProfile saved = service.updateProject("100", new QaValidationProfileCommand(
                "AUTO",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of("npm run build"),
                List.of("npm run typecheck")
        ));

        assertTrue(saved.buildCommandsDeclared());
        assertEquals(List.of("npm run build"), saved.buildCommands());
        assertTrue(saved.staticCommandsDeclared());
        assertEquals(List.of("npm run typecheck"), saved.staticCommands());
        QaValidationProfile loaded = service.getProject("100").orElseThrow();
        assertEquals(saved.buildCommands(), loaded.buildCommands());
        assertTrue(loaded.buildCommandsDeclared());
    }

    @Test
    void shouldPersistExplicitSkipSeparatelyFromUndeclaredCommands() {
        InMemoryStore store = new InMemoryStore();
        QaValidationProfileService service = new QaValidationProfileService(store);

        QaValidationProfile undeclared = service.updateProject("100", new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(), null, null));
        QaValidationProfile skipped = service.updateTask("200", new QaValidationProfileCommand(
                "AUTO", "", "", "", List.of(), List.of(), List.of(), List.of()));

        assertFalse(undeclared.buildCommandsDeclared());
        assertTrue(undeclared.buildCommands().isEmpty());
        assertTrue(skipped.buildCommandsDeclared());
        assertTrue(skipped.buildCommands().isEmpty());
    }

    private static QaValidationProfileCommand command(String baseUrl) {
        String host = java.net.URI.create(baseUrl).getHost();
        return new QaValidationProfileCommand(
                "REQUIRED",
                baseUrl,
                "npm run dev -- --host 0.0.0.0",
                "/health",
                java.util.List.of(host),
                java.util.List.of("npm test"),
                null,
                null
        );
    }

    private static final class InMemoryStore implements QaValidationProfileStore {
        private final Map<String, QaValidationProfile> profiles = new HashMap<>();

        @Override
        public QaValidationProfile save(QaValidationProfile profile) {
            profiles.put(profile.scopeType() + ":" + profile.scopeId(), profile);
            return profile;
        }

        @Override
        public Optional<QaValidationProfile> find(String scopeType, String scopeId) {
            return Optional.ofNullable(profiles.get(scopeType + ":" + scopeId));
        }
    }
}
