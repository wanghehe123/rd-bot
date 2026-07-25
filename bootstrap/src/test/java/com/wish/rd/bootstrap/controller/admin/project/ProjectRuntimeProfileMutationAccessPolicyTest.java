package com.wish.rd.bootstrap.controller.admin.project;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectRuntimeProfileMutationAccessPolicyTest {

    @Test
    void shouldFailClosedUntilAnUploadCapabilityTokenIsConfigured() {
        ProjectRuntimeProfileMutationAccessPolicy disabled = new ProjectRuntimeProfileMutationAccessPolicy(" ");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> disabled.requireAuthorized("anything")
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void shouldRequireExactCapabilityTokenForRuntimeMutations() {
        ProjectRuntimeProfileMutationAccessPolicy policy = new ProjectRuntimeProfileMutationAccessPolicy("local-secret");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> policy.requireAuthorized("wrong-secret")
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertDoesNotThrow(() -> policy.requireAuthorized("local-secret"));
    }
}
