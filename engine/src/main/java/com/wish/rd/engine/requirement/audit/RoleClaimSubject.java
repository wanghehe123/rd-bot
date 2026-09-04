package com.wish.rd.engine.requirement.audit;

import java.util.List;

/**
 * Executor self-reported claims recorded as {@code UNTRUSTED} facts.
 *
 * @param auditRunId audit run id
 * @param commandId durable command id
 * @param stageRunId source stage run
 * @param role agent role
 * @param claims scalar claims
 * @param facts {@code facts[]} entries
 * @param nowEpochMillis audit time
 */
public record RoleClaimSubject(
        String auditRunId,
        String commandId,
        String stageRunId,
        String role,
        List<RoleClaim> claims,
        List<RoleFact> facts,
        long nowEpochMillis
) {
    public static final int MAX_CLAIMS_PER_ATTEMPT = 32;

    public RoleClaimSubject {
        auditRunId = require(auditRunId, "auditRunId");
        commandId = require(commandId, "commandId");
        stageRunId = require(stageRunId, "stageRunId");
        role = require(role, "role");
        claims = claims == null ? List.of() : List.copyOf(claims);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
