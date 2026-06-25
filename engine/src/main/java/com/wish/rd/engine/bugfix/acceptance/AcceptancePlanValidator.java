package com.wish.rd.engine.bugfix.acceptance;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地确定性验收计划校验器。
 */
public final class AcceptancePlanValidator {

    public static AcceptancePlanValidator defaultValidator() {
        return new AcceptancePlanValidator();
    }

    public AcceptancePlanValidationResult validate(AcceptancePlan plan) {
        return validate(plan, "", "");
    }

    public AcceptancePlanValidationResult validate(
            AcceptancePlan plan,
            String expectedTaskId,
            String expectedTicketId
    ) {
        if (plan == null) {
            return AcceptancePlanValidationResult.failed(List.of("acceptance plan must not be null"));
        }
        List<String> errors = new ArrayList<>();
        if (plan.status() == AcceptancePlanStatus.READY) {
            validateReady(plan, errors);
            validateCurrentScope(plan, expectedTaskId, expectedTicketId, errors);
        } else {
            validateNonReady(plan, errors);
        }
        return errors.isEmpty()
                ? AcceptancePlanValidationResult.ok()
                : AcceptancePlanValidationResult.failed(errors);
    }

    private void validateCurrentScope(
            AcceptancePlan plan,
            String expectedTaskId,
            String expectedTicketId,
            List<String> errors
    ) {
        String safeTaskId = expectedTaskId == null ? "" : expectedTaskId.strip();
        String safeTicketId = expectedTicketId == null ? "" : expectedTicketId.strip();
        if (!safeTaskId.isBlank() && !plan.taskId().equals(safeTaskId)) {
            errors.add("taskId must match current task");
        }
        if (!safeTicketId.isBlank() && !plan.ticketId().equals(safeTicketId)) {
            errors.add("ticketId must match current ticket");
        }
    }

    private void validateReady(AcceptancePlan plan, List<String> errors) {
        if (plan.taskId().isBlank()) {
            errors.add("taskId must not be blank when acceptance plan is READY");
        }
        if (plan.ticketId().isBlank()) {
            errors.add("ticketId must not be blank when acceptance plan is READY");
        }
        if (plan.steps().isEmpty()) {
            errors.add("steps must not be empty when acceptance plan is READY");
        }
        if (plan.assertions().isEmpty()) {
            errors.add("assertions must not be empty when acceptance plan is READY");
        }
        for (AcceptancePlanStep step : plan.steps()) {
            if (step.description().isBlank()) {
                errors.add("step description must not be blank");
                break;
            }
        }
        for (AcceptanceAssertion assertion : plan.assertions()) {
            if (assertion.name().isBlank() || assertion.target().isBlank() || assertion.operator().isBlank()) {
                errors.add("assertion name, target and operator must not be blank");
                break;
            }
        }
    }

    private void validateNonReady(AcceptancePlan plan, List<String> errors) {
        if (plan.reason().isBlank()) {
            errors.add("reason must not be blank when acceptance plan is not READY");
        }
    }
}
