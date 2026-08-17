package com.wish.rd.engine.admin.observability.model;

import java.util.List;

/**
 * Read-only SLO eligibility and candidate report. Never mutates delivery state.
 *
 * @param observationOnly copied from settings
 * @param notificationsEnabled copied from settings
 * @param eligible true when min days and min samples are both met and data is available
 * @param ineligibilityReason empty when eligible
 * @param windowAgeDays observed window age used for the gate
 * @param terminalSamples judged terminal count used for the gate
 * @param candidates structured observations, including suppressed ones
 * @param firing candidates that also satisfied consecutive duration
 */
public record DeliverySloReport(
        boolean observationOnly,
        boolean notificationsEnabled,
        boolean eligible,
        String ineligibilityReason,
        long windowAgeDays,
        long terminalSamples,
        List<DeliveryAlertCandidate> candidates,
        List<DeliveryAlertCandidate> firing
) {

    public DeliverySloReport {
        ineligibilityReason = ineligibilityReason == null ? "" : ineligibilityReason;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        firing = firing == null ? List.of() : List.copyOf(firing);
        windowAgeDays = Math.max(0L, windowAgeDays);
        terminalSamples = Math.max(0L, terminalSamples);
    }
}
