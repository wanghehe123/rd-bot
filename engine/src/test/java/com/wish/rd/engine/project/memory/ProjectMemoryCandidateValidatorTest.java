package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryCandidate;
import com.wish.rd.rag.project.memory.model.ProjectMemoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectMemoryCandidateValidatorTest {

    private final ProjectMemoryCandidateValidator validator = new ProjectMemoryCandidateValidator(80);

    @Test
    void takesProjectAndSourceIdentityOnlyFromHostEvidenceAndRedactsDeterministically() {
        ProjectMemoryCandidate candidate = new ProjectMemoryCandidate(
                "999", "artifact-forged", "SEMANTIC", "subject", "title", "token=secret-value", "schema-1"
        );

        ProjectMemoryCandidate validated = validator.validate(
                candidate, new ProjectMemoryCaptureEvidence("101", "artifact-1", "a".repeat(64), "extractor-1")
        );

        assertEquals("101", validated.projectId());
        assertEquals("artifact-1", validated.sourceArtifactId());
        assertEquals(ProjectMemoryType.SEMANTIC, validated.memoryType());
        assertEquals("token=<redacted>", validated.summary());
    }

    @Test
    void rejectsProceduralCandidateWithMissingSourceOrOversizedContent() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                new ProjectMemoryCandidate("101", "", "PROCEDURAL", "subject", "", "summary", "schema-1"),
                new ProjectMemoryCaptureEvidence("101", "", "", "extractor-1")
        ));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                new ProjectMemoryCandidate("101", "artifact-1", "SEMANTIC", "subject", "", "x".repeat(81), "schema-1"),
                new ProjectMemoryCaptureEvidence("101", "artifact-1", "a".repeat(64), "extractor-1")
        ));
    }
}
