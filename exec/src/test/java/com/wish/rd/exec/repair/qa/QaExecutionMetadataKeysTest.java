package com.wish.rd.exec.repair.qa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QaExecutionMetadataKeysTest {

    @Test
    void workspaceFingerprintKeysStayOnTheDockerMetadataChannel() {
        assertEquals("qaWorkspaceFingerprintBeforeJson", QaExecutionMetadataKeys.WORKSPACE_FINGERPRINT_BEFORE_JSON);
        assertEquals("qaWorkspaceFingerprintAfterJson", QaExecutionMetadataKeys.WORKSPACE_FINGERPRINT_AFTER_JSON);
        assertEquals("qaWorkspaceIntegrity", QaExecutionMetadataKeys.WORKSPACE_INTEGRITY);
        assertEquals("CLEAN", QaExecutionMetadataKeys.WORKSPACE_INTEGRITY_CLEAN);
        assertEquals("VIOLATION", QaExecutionMetadataKeys.WORKSPACE_INTEGRITY_VIOLATION);
        assertEquals("qaDecisionSource", QaExecutionMetadataKeys.DECISION_SOURCE);
        assertEquals("qaCandidateChangedFilesJson", QaExecutionMetadataKeys.CANDIDATE_CHANGED_FILES_JSON);
    }
}
