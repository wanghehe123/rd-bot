package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderAttemptEvidenceTest {

    @Test
    void shouldCountProviderAttemptsFromJsonArray() {
        assertEquals(2, ProviderAttemptEvidence.count("""
                [
                  {"provider":"deepseek","status":"FAILED_VALIDATION"},
                  {"provider":"claude-code","status":"SUCCESS"}
                ]
                """));
    }

    @Test
    void shouldTreatBlankOrMalformedAttemptsAsZero() {
        assertEquals(0, ProviderAttemptEvidence.count(""));
        assertEquals(0, ProviderAttemptEvidence.count("not-json"));
    }

    @Test
    void shouldDetectFallbackFromFailedAttemptToSuccessfulProvider() {
        ProviderAttemptEvidence.FallbackEvidence evidence = ProviderAttemptEvidence.fallback("""
                [
                  {"provider":"deepseek","status":"FAILED_VALIDATION"},
                  {"provider":"claude-code","status":"SUCCESS"}
                ]
                """);

        assertTrue(evidence.detected());
        assertEquals("deepseek", evidence.failedProvider());
        assertEquals("FAILED_VALIDATION", evidence.failedStatus());
        assertEquals("claude-code", evidence.activeProvider());
    }

    @Test
    void shouldNotDetectFallbackWithoutFailureBeforeSuccess() {
        ProviderAttemptEvidence.FallbackEvidence evidence = ProviderAttemptEvidence.fallback("""
                [
                  {"provider":"claude-code","status":"SUCCESS"}
                ]
                """);

        assertFalse(evidence.detected());
    }

    @Test
    void shouldNotDetectFallbackWhenSuccessfulAttemptDoesNotIdentifyADifferentProvider() {
        ProviderAttemptEvidence.FallbackEvidence missingActiveProvider = ProviderAttemptEvidence.fallback("""
                [
                  {"provider":"deepseek","status":"FAILED_VALIDATION"},
                  {"status":"SUCCESS"}
                ]
                """);
        assertFalse(missingActiveProvider.detected());

        ProviderAttemptEvidence.FallbackEvidence sameProviderRetry = ProviderAttemptEvidence.fallback("""
                [
                  {"provider":"deepseek","status":"FAILED_VALIDATION"},
                  {"provider":"deepseek","status":"SUCCESS"}
                ]
                """);
        assertFalse(sameProviderRetry.detected());
    }
}
