package com.wish.rd.exec.repair.code.model;

/**
 * Query for an existing open pull request by repository head/base branches.
 *
 * @param repoOwner  repository owner
 * @param repoName   repository name
 * @param baseBranch target base branch
 * @param workBranch candidate head branch
 */
public record FindOpenPullRequestCommand(
        String repoOwner,
        String repoName,
        String baseBranch,
        String workBranch
) {

    public FindOpenPullRequestCommand {
        repoOwner = require(repoOwner, "repoOwner");
        repoName = require(repoName, "repoName");
        baseBranch = require(baseBranch, "baseBranch");
        workBranch = require(workBranch, "workBranch");
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
