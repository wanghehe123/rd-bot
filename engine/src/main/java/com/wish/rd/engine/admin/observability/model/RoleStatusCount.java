package com.wish.rd.engine.admin.observability.model;

/**
 * Role/status count for the Prometheus stage series.
 *
 * @param role allowlisted role
 * @param status stage status
 * @param count rows
 */
public record RoleStatusCount(String role, String status, long count) {

    public RoleStatusCount {
        role = role == null ? "" : role.strip();
        status = status == null ? "" : status.strip();
        count = Math.max(0L, count);
    }
}
