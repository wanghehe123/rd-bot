package com.wish.rd.engine.admin.projectmemory;

/** Stores short-lived purge confirmation tokens bound to operator, project and preview counts. */
public interface ProjectMemoryPurgeConfirmTokenStore {

    ProjectMemoryPurgeConfirmToken issue(
            String projectId,
            String operatorId,
            String reason,
            int expectedTotalRowCount,
            long expiresAtEpochMillis
    );

    ProjectMemoryPurgeConfirmToken consume(String projectId, String confirmToken, String operatorId, long nowEpochMillis);
}
