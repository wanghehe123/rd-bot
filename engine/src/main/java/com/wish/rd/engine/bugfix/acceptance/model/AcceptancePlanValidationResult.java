package com.wish.rd.engine.bugfix.acceptance.model;

import java.util.List;

/**
 * 验收计划校验结果。
 *
 * @param valid  是否有效
 * @param errors 错误列表
 */
public record AcceptancePlanValidationResult(
        boolean valid,
        List<String> errors
) {

    public AcceptancePlanValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static AcceptancePlanValidationResult ok() {
        return new AcceptancePlanValidationResult(true, List.of());
    }

    public static AcceptancePlanValidationResult failed(List<String> errors) {
        return new AcceptancePlanValidationResult(false, errors);
    }
}
