package com.wish.rd.exec.repair.result.model;

import java.util.List;

/**
 * Agent 角色产物校验结果。
 *
 * @param valid  是否通过校验
 * @param errors 错误列表
 */
public record AgentRoleResultValidation(boolean valid, List<String> errors) {

    public AgentRoleResultValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
