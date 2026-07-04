package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryReviewFailureRealSmokePreconditionsTest {

    @Test
    void shouldListMissingRequiredDeliveryReviewFailureSmokeProperties() {
        java.util.List<String> missing = DeliveryReviewFailureRealSmokeTest.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.delivery-review-failure.smoke.production-evidence"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.base-url"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.postgres-url"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.task-id"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.review-artifact-uri"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.feishu-alert-message-id"));
    }
}
