package com.wish.rd.exec.repair.result;

import java.util.List;

/**
 * 结构化修复结果校验返回值，供执行编排在不抛出异常的情况下识别 agent 输出问题。
 *
 * @param valid  是否通过协议校验
 * @param result 解析成功后的结构化结果；JSON 解析失败时为空
 * @param errors 校验错误列表
 */
public record StructuredResultValidation(
        boolean valid,
        StructuredRepairResult result,
        List<String> errors
) {

    public StructuredResultValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
