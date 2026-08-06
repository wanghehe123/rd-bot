package com.wish.rd.exec.repair.code.model;

/**
 * Query for a remote branch tip SHA.
 *
 * @param repoOwner repository owner
 * @param repoName  repository name
 * @param branch    branch name (may contain slashes)
 */
public record FindBranchHeadCommand(
        String repoOwner,
        String repoName,
        String branch
) {

    public FindBranchHeadCommand {
        repoOwner = require(repoOwner, "repoOwner");
        repoName = require(repoName, "repoName");
        branch = require(branch, "branch");
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
