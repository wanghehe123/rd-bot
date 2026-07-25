package com.wish.rd.bootstrap.controller.admin.project;

import com.wish.rd.bootstrap.executor.impl.ProjectRuntimeProfileUploadService;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.impl.InMemoryProjectRuntimeProfileStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectRuntimeProfileControllerTest {

    @Test
    void shouldRequireCapabilityTokenBeforeDeletingRuntimeProfile() {
        ProjectRuntimeProfileController controller = new ProjectRuntimeProfileController(
                new ProjectRuntimeProfileService(new InMemoryProjectRuntimeProfileStore()),
                emptyProvider(),
                new ProjectRuntimeProfileMutationAccessPolicy("operator-token")
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.delete("project-1", "CODING_AGENT", "wrong-token")
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        Map<String, Boolean> result = controller.delete("project-1", "CODING_AGENT", "operator-token");
        assertFalse(result.get("deleted"));
    }

    private static ObjectProvider<ProjectRuntimeProfileUploadService> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public ProjectRuntimeProfileUploadService getObject() {
                return null;
            }

            @Override
            public ProjectRuntimeProfileUploadService getIfAvailable() {
                return null;
            }

            @Override
            public ProjectRuntimeProfileUploadService getIfUnique() {
                return null;
            }
        };
    }
}
