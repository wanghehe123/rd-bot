package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementReviewBlockerRealSmokePreconditionsTest {

    @Test
    void shouldRequireRealHttpPostgresArtifactAndFeishuProperties() {
        List<String> missing = RequirementReviewBlockerRealSmokeTest.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.requirement-review.smoke.production-evidence"));
        assertTrue(missing.contains("rd.requirement-review.smoke.base-url"));
        assertTrue(missing.contains("rd.requirement-review.smoke.postgres-url"));
        assertTrue(missing.contains("rd.requirement-review.smoke.task-id"));
        assertTrue(missing.contains("rd.requirement-review.smoke.review-artifact-uri"));
        assertTrue(missing.contains("rd.requirement-review.smoke.feishu-alert-message-id"));
    }
}
