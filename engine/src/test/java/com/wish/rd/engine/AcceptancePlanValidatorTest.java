package com.wish.rd.engine;

import com.wish.rd.engine.bugfix.acceptance.AcceptanceAssertion;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlan;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanStatus;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanStep;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanValidationResult;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptancePlanValidatorTest {

    @Test
    void readyPlanRequiresTaskTicketStepsAndAssertions() {
        AcceptancePlan plan = new AcceptancePlan(
                "",
                "FS-1",
                AcceptancePlanStatus.READY,
                "docker-claude-planner",
                "",
                List.of(new AcceptancePlanStep("execute", "http", "POST /api/orders", "{}")),
                List.of(new AcceptanceAssertion("status", "http.status", "eq", "200")),
                List.of("chunk-1")
        );

        AcceptancePlanValidationResult result = AcceptancePlanValidator.defaultValidator().validate(plan);

        assertFalse(result.valid());
        assertTrue(result.errors().contains("taskId must not be blank when acceptance plan is READY"));
    }

    @Test
    void disabledPlanRequiresReasonButDoesNotRequireSteps() {
        AcceptancePlan invalid = AcceptancePlan.disabled("task-1", "FS-1", "");
        AcceptancePlan valid = AcceptancePlan.disabled("task-1", "FS-1", "acceptance planner is not configured");

        assertFalse(AcceptancePlanValidator.defaultValidator().validate(invalid).valid());
        assertTrue(AcceptancePlanValidator.defaultValidator().validate(valid).valid());
        assertTrue(valid.toPromptSection().contains("最终验收裁判"));
        assertTrue(valid.toPromptSection().contains("不能自行判定最终验收通过"));
    }

    @Test
    void readyPlanMustMatchExpectedTaskAndTicket() {
        AcceptancePlan mismatched = new AcceptancePlan(
                "other-task",
                "OTHER-FS",
                AcceptancePlanStatus.READY,
                "docker-claude-planner",
                "",
                List.of(new AcceptancePlanStep("execute", "http", "POST /api/orders", "{}")),
                List.of(new AcceptanceAssertion("status", "http.status", "eq", "200")),
                List.of("chunk-1")
        );

        AcceptancePlanValidationResult result = AcceptancePlanValidator.defaultValidator()
                .validate(mismatched, "task-1", "FS-1");

        assertFalse(result.valid());
        assertTrue(result.errors().contains("taskId must match current task"));
        assertTrue(result.errors().contains("ticketId must match current ticket"));
    }

    @Test
    void promptSectionStatesPlanSourceAndRdBotFinalValidation() {
        AcceptancePlan plan = new AcceptancePlan(
                "task-1",
                "FS-1",
                AcceptancePlanStatus.READY,
                "docker-claude-planner",
                "",
                List.of(new AcceptancePlanStep("execute", "http", "POST /api/orders", "{}")),
                List.of(new AcceptanceAssertion("status", "http.status", "eq", "200")),
                List.of("chunk-1")
        );

        String promptSection = plan.toPromptSection();

        assertTrue(promptSection.contains("docker-claude-planner"));
        assertTrue(promptSection.contains("POST /api/orders"));
        assertTrue(promptSection.contains("http.status"));
        assertTrue(promptSection.contains("RD-Bot"));
        assertTrue(promptSection.contains("最终验收裁判"));
    }
}
