package com.wish.rd.engine.bugfix.acceptance;

import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlan;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanGenerationCommand;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanGenerationResult;


/**
 * 验收计划生成端口。
 *
 * <p>实现可以是 Docker Claude Code planner，但 engine 只依赖该端口和本地校验结果。
 */
@FunctionalInterface
public interface AcceptancePlanGeneratorPort {

    AcceptancePlanGenerationResult generate(AcceptancePlanGenerationCommand command);

    static AcceptancePlanGeneratorPort disabled() {
        return command -> new AcceptancePlanGenerationResult(AcceptancePlan.disabled(
                command == null ? "" : command.taskId(),
                command == null ? "" : command.ticketId(),
                "acceptance planner is not configured"
        ));
    }
}
