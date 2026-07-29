package com.wish.rd.engine.evaluation.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, pre-registered metadata for one coding benchmark case.
 *
 * <p>The actual repository revision, image digests, tests and knowledge snapshot are frozen by
 * the readiness manifest. This record retains the fields needed to validate the campaign matrix
 * before it is dispatched.</p>
 */
public record CodingBenchmarkCase(
        String caseId,
        CodingBenchmarkSlice slice,
        String repositoryId,
        String language,
        String difficulty
) {
    private static final Pattern CASE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    /** Normalizes required case metadata and rejects invalid logical identifiers. */
    public CodingBenchmarkCase {
        caseId = requireCaseId(caseId);
        slice = Objects.requireNonNull(slice, "slice must not be null");
        repositoryId = requireText(repositoryId, "repository id");
        language = requireText(language, "language");
        difficulty = requireText(difficulty, "difficulty");
    }

    private static String requireCaseId(String value) {
        String normalized = requireText(value, "case id");
        if (!CASE_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("case id contains unsupported characters");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
