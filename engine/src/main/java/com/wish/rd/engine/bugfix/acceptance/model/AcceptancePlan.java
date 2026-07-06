package com.wish.rd.engine.bugfix.acceptance.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RD-Bot 验收计划。
 *
 * <p>计划可以由 Docker Claude Code planner 生成，但必须由 RD-Bot 本地校验后才能传给修复执行器。
 *
 * @param taskId           任务 ID
 * @param ticketId         工单 ID
 * @param status           计划状态
 * @param source           计划来源
 * @param reason           非 READY 状态原因
 * @param steps            执行步骤
 * @param assertions       验收断言
 * @param evidenceChunkIds 生成依据
 */
public record AcceptancePlan(
        String taskId,
        String ticketId,
        AcceptancePlanStatus status,
        String source,
        String reason,
        List<AcceptancePlanStep> steps,
        List<AcceptanceAssertion> assertions,
        List<String> evidenceChunkIds
) {

    public AcceptancePlan {
        taskId = taskId == null ? "" : taskId.strip();
        ticketId = ticketId == null ? "" : ticketId.strip();
        status = status == null ? AcceptancePlanStatus.DISABLED : status;
        source = source == null ? "" : source.strip();
        reason = reason == null ? "" : reason.strip();
        steps = steps == null ? List.of() : List.copyOf(steps);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        evidenceChunkIds = evidenceChunkIds == null
                ? List.of()
                : evidenceChunkIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::strip)
                .toList();
    }

    public static AcceptancePlan disabled(String taskId, String ticketId, String reason) {
        return new AcceptancePlan(
                taskId,
                ticketId,
                AcceptancePlanStatus.DISABLED,
                "rd-bot-local-default",
                reason,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static AcceptancePlan unsupported(String taskId, String ticketId, String source, String reason) {
        return new AcceptancePlan(
                taskId,
                ticketId,
                AcceptancePlanStatus.UNSUPPORTED,
                source,
                reason,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public String toPromptSection() {
        if (status != AcceptancePlanStatus.READY) {
            return """
                    ## 验收计划
                    - 状态：%s
                    - 来源：%s
                    - 原因：%s

                    RD-Bot 未启用可执行验收计划。你仍需执行与工单直接相关的自测，并在结构化结果中说明测试证据。
                    RD-Bot 是最终验收裁判。Docker Claude Code 可以参考本计划修复和自测，但不能自行判定最终验收通过。
                    """.formatted(status.name(), source, reason).strip();
        }
        return """
                ## 验收计划
                - 状态：READY
                - 来源：%s
                - 依据：%s

                ### 验收步骤
                %s

                ### 验收断言
                %s

                RD-Bot 是最终验收裁判。Docker Claude Code 可以参考本计划修复和自测，但不能自行判定最终验收通过。
                """.formatted(
                source,
                evidenceChunkIds.isEmpty() ? "无显式 evidence chunk" : String.join(",", evidenceChunkIds),
                stepLines(),
                assertionLines()
        ).strip();
    }

    public String toJson() {
        return """
                {"taskId":"%s","ticketId":"%s","status":"%s","source":"%s","reason":"%s","steps":[%s],"assertions":[%s],"evidenceChunkIds":[%s]}
                """.formatted(
                json(taskId),
                json(ticketId),
                status.name(),
                json(source),
                json(reason),
                steps.stream().map(this::stepJson).collect(Collectors.joining(",")),
                assertions.stream().map(this::assertionJson).collect(Collectors.joining(",")),
                evidenceChunkIds.stream().map(id -> "\"" + json(id) + "\"").collect(Collectors.joining(","))
        ).strip();
    }

    private String stepLines() {
        if (steps.isEmpty()) {
            return "- 无";
        }
        return steps.stream()
                .map(step -> "- [%s/%s] %s input=%s".formatted(
                        step.stepType(), step.tool(), step.description(), step.inputJson()))
                .collect(Collectors.joining("\n"));
    }

    private String assertionLines() {
        if (assertions.isEmpty()) {
            return "- 无";
        }
        return assertions.stream()
                .map(assertion -> "- %s: %s %s %s".formatted(
                        assertion.name(), assertion.target(), assertion.operator(), assertion.expected()))
                .collect(Collectors.joining("\n"));
    }

    private String stepJson(AcceptancePlanStep step) {
        return """
                {"stepType":"%s","tool":"%s","description":"%s","inputJson":"%s"}
                """.formatted(
                json(step.stepType()),
                json(step.tool()),
                json(step.description()),
                json(step.inputJson())
        ).strip();
    }

    private String assertionJson(AcceptanceAssertion assertion) {
        return """
                {"name":"%s","target":"%s","operator":"%s","expected":"%s"}
                """.formatted(
                json(assertion.name()),
                json(assertion.target()),
                json(assertion.operator()),
                json(assertion.expected())
        ).strip();
    }

    private static String json(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
