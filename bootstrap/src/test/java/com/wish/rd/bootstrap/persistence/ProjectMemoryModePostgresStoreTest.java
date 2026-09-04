package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresProjectMemoryModeStore;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryModeMapper;
import com.wish.rd.rag.project.memory.model.ProjectMemoryCaptureMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectMemoryModePostgresStoreTest {
    @Test
    void rejectsNonNumericProjectIdBeforeAnyPostgresAccess() {
        ProjectMemoryModeMapper mapper = mock(ProjectMemoryModeMapper.class);
        PostgresProjectMemoryModeStore store = new PostgresProjectMemoryModeStore(mapper);

        assertThrows(IllegalArgumentException.class, () -> store.getConfig("repository-fallback"));
    }

    @Test
    void defaultsToOffWhenNoRowExists() {
        ProjectMemoryModeMapper mapper = mock(ProjectMemoryModeMapper.class);
        when(mapper.findByProjectId(101L)).thenReturn(null);
        PostgresProjectMemoryModeStore store = new PostgresProjectMemoryModeStore(mapper);

        ProjectMemoryModeConfig config = store.getConfig("101");
        assertEquals(ProjectMemoryCaptureMode.OFF, config.captureMode());
        assertEquals(ProjectMemoryReadMode.LEGACY, config.readMode());
    }

    @Test
    void upsertsIndependentCaptureAndReadModes() {
        ProjectMemoryModeMapper mapper = mock(ProjectMemoryModeMapper.class);
        PostgresProjectMemoryModeStore store = new PostgresProjectMemoryModeStore(mapper);
        store.save("101", ProjectMemoryMode.PRIMARY);

        verify(mapper).upsert(any());
    }

    @Test
    void migrationDefinesIndependentCaptureAndReadModes() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p19_project_agent_memory.sql");
        String content = Files.readString(sql);
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_memory_modes"));
        assertTrue(content.contains("capture_mode IN ('OFF', 'SHADOW', 'ACTIVE')"));
        assertTrue(content.contains("read_mode IN ('LEGACY', 'SHADOW', 'DUAL', 'PRIMARY')"));
        assertTrue(content.contains("REFERENCES rd_projects(id) ON DELETE RESTRICT"));
    }
}
