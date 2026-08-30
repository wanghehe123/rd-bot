package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryCandidate;

import java.util.regex.Pattern;

/** Validates and redacts untrusted extractor output before any persistence or promotion decision. */
public final class ProjectMemoryCandidateValidator {
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(token|api[_-]?key|password|secret|authorization)\\s*[=:]\\s*[^\\s,;]+"
    );
    private final int maximumSummaryLength;

    public ProjectMemoryCandidateValidator(int maximumSummaryLength) {
        if (maximumSummaryLength < 1) throw new IllegalArgumentException("maximumSummaryLength must be positive");
        this.maximumSummaryLength = maximumSummaryLength;
    }

    public ProjectMemoryCandidate validate(ProjectMemoryCandidate candidate, ProjectMemoryCaptureEvidence evidence) {
        if (candidate == null || evidence == null) throw new IllegalArgumentException("candidate and evidence are required");
        if (!evidence.projectId().matches("[1-9][0-9]*")) throw new IllegalArgumentException("host project identity is required");
        if (evidence.sourceArtifactId().isBlank() || !evidence.sourceContentHash().matches("[0-9a-f]{64}")
                || evidence.extractorVersion().isBlank()) throw new IllegalArgumentException("verifiable host source is required");
        if (candidate.logicalKey().isBlank() || candidate.schemaVersion().isBlank()) {
            throw new IllegalArgumentException("candidate logicalKey and schemaVersion are required");
        }
        if (candidate.summary().length() > maximumSummaryLength) throw new IllegalArgumentException("candidate summary exceeds maximum length");
        return candidate.withHostIdentity(evidence.projectId(), evidence.sourceArtifactId(), redact(candidate.summary()));
    }

    private static String redact(String value) {
        return SENSITIVE.matcher(value == null ? "" : value).replaceAll("$1=<redacted>");
    }
}
