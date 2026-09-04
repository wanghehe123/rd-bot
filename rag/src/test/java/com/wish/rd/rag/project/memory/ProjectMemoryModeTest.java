package com.wish.rd.rag.project.memory;

import com.wish.rd.rag.project.memory.impl.InMemoryProjectMemoryModeStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryCaptureMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryModeTest {
    @Test
    void defaultsToOffAndMapsIndependentCaptureAndReadModesWithoutDeletingData() {
        InMemoryProjectMemoryModeStore store = new InMemoryProjectMemoryModeStore();
        assertEquals(ProjectMemoryMode.OFF, store.find("101"));
        assertEquals(ProjectMemoryCaptureMode.OFF, store.getConfig("101").captureMode());
        assertEquals(ProjectMemoryReadMode.LEGACY, store.getConfig("101").readMode());

        store.save("101", ProjectMemoryMode.PRIMARY);
        assertEquals(ProjectMemoryMode.PRIMARY, store.find("101"));
        store.save("101", ProjectMemoryMode.OFF);
        assertEquals(ProjectMemoryMode.OFF, store.find("101"));
        assertEquals(ProjectMemoryCaptureMode.OFF, store.getConfig("101").captureMode());
        assertEquals(ProjectMemoryReadMode.LEGACY, store.getConfig("101").readMode());
    }

    @Test
    void mapsNamedPoliciesToIndependentModes() {
        assertEquals(ProjectMemoryMode.OFF, ProjectMemoryMode.fromModes(
                ProjectMemoryCaptureMode.OFF, ProjectMemoryReadMode.LEGACY).orElseThrow());
        assertEquals(ProjectMemoryMode.SHADOW, ProjectMemoryMode.fromModes(
                ProjectMemoryCaptureMode.SHADOW, ProjectMemoryReadMode.SHADOW).orElseThrow());
        assertEquals(ProjectMemoryMode.DUAL_READ, ProjectMemoryMode.fromModes(
                ProjectMemoryCaptureMode.ACTIVE, ProjectMemoryReadMode.DUAL).orElseThrow());
        assertEquals(ProjectMemoryMode.PRIMARY, ProjectMemoryMode.fromModes(
                ProjectMemoryCaptureMode.ACTIVE, ProjectMemoryReadMode.PRIMARY).orElseThrow());
    }

    @Test
    void persistsIndependentCaptureAndReadModes() {
        InMemoryProjectMemoryModeStore store = new InMemoryProjectMemoryModeStore();
        ProjectMemoryModeService service = new ProjectMemoryModeService(store);

        ProjectMemoryModeConfig updated = service.updateModes(
                "101", ProjectMemoryCaptureMode.ACTIVE, ProjectMemoryReadMode.DUAL);
        assertEquals(ProjectMemoryCaptureMode.ACTIVE, updated.captureMode());
        assertEquals(ProjectMemoryReadMode.DUAL, updated.readMode());
        assertEquals(ProjectMemoryMode.DUAL_READ, updated.namedPolicy().orElseThrow());
    }

    @Test
    void failsClosedForInvalidOrDisabledProjectIdentity() {
        InMemoryProjectMemoryModeStore store = new InMemoryProjectMemoryModeStore();
        ProjectMemoryModeService service = new ProjectMemoryModeService(store);
        assertThrows(IllegalArgumentException.class, () -> store.save("repo-fallback", ProjectMemoryMode.SHADOW));
        assertFalse(service.shouldRunExtractor("101", false, false));
        assertFalse(service.shouldInjectProjectMemory("101", true, true));
        assertFalse(service.shouldIncludeLegacyExperience("101", false, false));
    }

    @Test
    void routesCaptureAndReadByModeWithoutReplayingOldMarkers() {
        ProjectMemoryModeService service = new ProjectMemoryModeService(new InMemoryProjectMemoryModeStore());
        service.updatePolicy("101", ProjectMemoryMode.SHADOW);
        assertTrue(service.shouldRunExtractor("101", true, false));
        assertFalse(service.shouldInjectProjectMemory("101", true, false));
        assertTrue(service.shouldShadowAuditProjectMemory("101", true, false));
        assertTrue(service.shouldIncludeLegacyExperience("101", true, false));

        service.updatePolicy("101", ProjectMemoryMode.PRIMARY);
        assertTrue(service.shouldRunExtractor("101", true, false));
        assertTrue(service.shouldInjectProjectMemory("101", true, false));
        assertFalse(service.shouldIncludeLegacyExperience("101", true, false));

        service.updatePolicy("101", ProjectMemoryMode.OFF);
        assertFalse(service.shouldRunExtractor("101", true, false));
        assertTrue(ProjectMemoryReadRoutingPolicy.shouldRegisterSkippedMarker(
                service.get("101"), true, false));
    }
}
