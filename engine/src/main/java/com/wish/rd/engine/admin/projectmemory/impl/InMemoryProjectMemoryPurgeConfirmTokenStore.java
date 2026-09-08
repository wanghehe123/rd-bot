package com.wish.rd.engine.admin.projectmemory.impl;

import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmTokenExpiredException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmTokenInvalidException;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmTokenStore;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryPurgeConfirmToken;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-process confirm token store for purge preview/execute handoff. */
public final class InMemoryProjectMemoryPurgeConfirmTokenStore implements ProjectMemoryPurgeConfirmTokenStore {

    private final Map<String, ProjectMemoryPurgeConfirmToken> tokens = new ConcurrentHashMap<>();

    @Override
    public ProjectMemoryPurgeConfirmToken issue(
            String projectId,
            String operatorId,
            String reason,
            int expectedTotalRowCount,
            long expiresAtEpochMillis
    ) {
        String token = UUID.randomUUID().toString();
        ProjectMemoryPurgeConfirmToken confirmToken = new ProjectMemoryPurgeConfirmToken(
                token, projectId, operatorId, reason, expectedTotalRowCount, expiresAtEpochMillis
        );
        tokens.put(tokenKey(projectId, token), confirmToken);
        return confirmToken;
    }

    @Override
    public ProjectMemoryPurgeConfirmToken consume(
            String projectId,
            String confirmToken,
            String operatorId,
            long nowEpochMillis
    ) {
        String normalizedToken = confirmToken == null ? "" : confirmToken.strip();
        if (normalizedToken.isEmpty()) {
            throw new ProjectMemoryPurgeConfirmTokenInvalidException("confirm token must not be blank");
        }
        ProjectMemoryPurgeConfirmToken stored = tokens.remove(tokenKey(projectId, normalizedToken));
        if (stored == null) {
            throw new ProjectMemoryPurgeConfirmTokenInvalidException("confirm token is invalid or already used");
        }
        if (!stored.operatorId().equals(operatorId)) {
            throw new ProjectMemoryPurgeConfirmTokenInvalidException("confirm token operator mismatch");
        }
        if (stored.expiredAt(nowEpochMillis)) {
            throw new ProjectMemoryPurgeConfirmTokenExpiredException("confirm token expired");
        }
        return stored;
    }

    private static String tokenKey(String projectId, String token) {
        return projectId + "|" + token;
    }
}
