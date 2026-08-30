package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryModeStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryCaptureMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectMemoryModeServiceTest {
    @Test
    void switchingPolicyUpdatesModesWithoutDeletingPersistedConfiguration() {
        InMemoryProjectMemoryModeStore store = new InMemoryProjectMemoryModeStore();
        ProjectMemoryModeService service = new ProjectMemoryModeService(store);

        service.updatePolicy("101", ProjectMemoryMode.DUAL_READ);
        assertEquals(ProjectMemoryCaptureMode.ACTIVE, store.getConfig("101").captureMode());
        assertEquals(ProjectMemoryReadMode.DUAL, store.getConfig("101").readMode());

        service.updatePolicy("101", ProjectMemoryMode.OFF);
        assertEquals(ProjectMemoryCaptureMode.OFF, store.getConfig("101").captureMode());
        assertEquals(ProjectMemoryReadMode.LEGACY, store.getConfig("101").readMode());

        service.updatePolicy("101", ProjectMemoryMode.PRIMARY);
        assertEquals(ProjectMemoryMode.PRIMARY, store.find("101"));
    }
}
