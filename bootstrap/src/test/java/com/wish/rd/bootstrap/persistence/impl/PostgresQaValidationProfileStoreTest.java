package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.QaValidationProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.QaValidationProfileMapper;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
