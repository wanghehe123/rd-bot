package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.QaValidationProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.QaValidationProfileMapper;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresQaValidationProfileStoreTest {

    @Test
    void shouldRoundTripAllowedHostsAndRegressionCommands() {
        QaValidationProfileMapper mapper = mock(QaValidationProfileMapper.class);
        PostgresQaValidationProfileStore store = new PostgresQaValidationProfileStore(mapper, new ObjectMapper());
        QaValidationProfile profile = new QaValidationProfile(
                "PROJECT",
                "7482000000000000701",
                "REQUIRED",
                "http://127.0.0.1:5173",
                "npm run dev -- --host 0.0.0.0",
                "/health",
                List.of("127.0.0.1", "localhost"),
                List.of("npm test"),
                List.of(),
                List.of(),
                false,
                false,
                1_783_200_000_000L,
                1_783_200_001_000L
        );

        store.save(profile);
        verify(mapper).upsert(any(QaValidationProfileRow.class));

        QaValidationProfileRow row = new QaValidationProfileRow();
        row.scopeType = "PROJECT";
        row.scopeId = 7_482_000_000_000_000_701L;
        row.mode = "REQUIRED";
        row.baseUrl = profile.baseUrl();
        row.startCommand = profile.startCommand();
        row.healthPath = profile.healthPath();
        row.allowedHostsJson = "[\"127.0.0.1\",\"localhost\"]";
        row.regressionCommandsJson = "[\"npm test\"]";
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = OffsetDateTime.now();
        when(mapper.find("PROJECT", row.scopeId)).thenReturn(row);

        QaValidationProfile loaded = store.find("PROJECT", profile.scopeId()).orElseThrow();
        assertEquals(profile.allowedHosts(), loaded.allowedHosts());
        assertEquals(profile.regressionCommands(), loaded.regressionCommands());
        assertFalse(loaded.buildCommandsDeclared());
        assertFalse(loaded.staticCommandsDeclared());
    }

    @Test
    void shouldWriteNullJsonWhenBuildCommandsAreUndeclared() {
        QaValidationProfileMapper mapper = mock(QaValidationProfileMapper.class);
        PostgresQaValidationProfileStore store = new PostgresQaValidationProfileStore(mapper, new ObjectMapper());

        store.save(profile(false, List.of(), false, List.of()));

        ArgumentCaptor<QaValidationProfileRow> captor = ArgumentCaptor.forClass(QaValidationProfileRow.class);
        verify(mapper).upsert(captor.capture());
        assertNull(captor.getValue().buildCommandsJson);
        assertNull(captor.getValue().staticCommandsJson);
    }

    @Test
    void shouldWriteEmptyArrayWhenBuildCommandsAreExplicitSkip() {
        QaValidationProfileMapper mapper = mock(QaValidationProfileMapper.class);
        PostgresQaValidationProfileStore store = new PostgresQaValidationProfileStore(mapper, new ObjectMapper());

        store.save(profile(true, List.of(), true, List.of()));

        ArgumentCaptor<QaValidationProfileRow> captor = ArgumentCaptor.forClass(QaValidationProfileRow.class);
        verify(mapper).upsert(captor.capture());
        assertEquals("[]", captor.getValue().buildCommandsJson);
        assertEquals("[]", captor.getValue().staticCommandsJson);
    }

    @Test
    void shouldWriteCommandArrayWhenDeclaredNonEmpty() {
        QaValidationProfileMapper mapper = mock(QaValidationProfileMapper.class);
        PostgresQaValidationProfileStore store = new PostgresQaValidationProfileStore(mapper, new ObjectMapper());

        store.save(profile(true, List.of("npm run build"), true, List.of("npm run typecheck")));

        ArgumentCaptor<QaValidationProfileRow> captor = ArgumentCaptor.forClass(QaValidationProfileRow.class);
        verify(mapper).upsert(captor.capture());
        assertEquals("[\"npm run build\"]", captor.getValue().buildCommandsJson);
        assertEquals("[\"npm run typecheck\"]", captor.getValue().staticCommandsJson);
    }

    @Test
    void shouldTreatSqlNullAndJsonNullAsUndeclared() {
        QaValidationProfileMapper mapper = mock(QaValidationProfileMapper.class);
        PostgresQaValidationProfileStore store = new PostgresQaValidationProfileStore(mapper, new ObjectMapper());
        QaValidationProfileRow row = baseRow();
        row.buildCommandsJson = null;
        row.staticCommandsJson = "null";
        when(mapper.find("PROJECT", row.scopeId)).thenReturn(row);

        QaValidationProfile loaded = store.find("PROJECT", "7482000000000000701").orElseThrow();
        assertFalse(loaded.buildCommandsDeclared());
        assertTrue(loaded.buildCommands().isEmpty());
        assertFalse(loaded.staticCommandsDeclared());
        assertTrue(loaded.staticCommands().isEmpty());
    }

    @Test
    void shouldTreatEmptyArrayAsExplicitSkip() {
        QaValidationProfileMapper mapper = mock(QaValidationProfileMapper.class);
        PostgresQaValidationProfileStore store = new PostgresQaValidationProfileStore(mapper, new ObjectMapper());
        QaValidationProfileRow row = baseRow();
        row.buildCommandsJson = "[]";
        row.staticCommandsJson = "[]";
        when(mapper.find("PROJECT", row.scopeId)).thenReturn(row);

        QaValidationProfile loaded = store.find("PROJECT", "7482000000000000701").orElseThrow();
        assertTrue(loaded.buildCommandsDeclared());
        assertTrue(loaded.buildCommands().isEmpty());
        assertTrue(loaded.staticCommandsDeclared());
        assertTrue(loaded.staticCommands().isEmpty());
    }

    private static QaValidationProfile profile(
            boolean buildDeclared,
            List<String> buildCommands,
            boolean staticDeclared,
            List<String> staticCommands
    ) {
        return new QaValidationProfile(
                "PROJECT",
                "7482000000000000701",
                "AUTO",
                "",
                "",
                "",
                List.of(),
                List.of(),
                buildCommands,
                staticCommands,
                buildDeclared,
                staticDeclared,
                1_783_200_000_000L,
                1_783_200_001_000L
        );
    }

    private static QaValidationProfileRow baseRow() {
        QaValidationProfileRow row = new QaValidationProfileRow();
        row.scopeType = "PROJECT";
        row.scopeId = 7_482_000_000_000_000_701L;
        row.mode = "AUTO";
        row.baseUrl = "";
        row.startCommand = "";
        row.healthPath = "";
        row.allowedHostsJson = "[]";
        row.regressionCommandsJson = "[]";
        row.createdAt = OffsetDateTime.now();
        row.updatedAt = OffsetDateTime.now();
        return row;
    }
}
