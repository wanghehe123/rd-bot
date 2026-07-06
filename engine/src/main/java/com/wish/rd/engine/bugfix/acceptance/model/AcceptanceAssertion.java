package com.wish.rd.engine.bugfix.acceptance.model;

/**
 * 验收断言。
 *
 * @param name     断言名称
 * @param target   断言目标
 * @param operator 操作符
 * @param expected 期望值
 */
public record AcceptanceAssertion(
        String name,
        String target,
        String operator,
        String expected
) {

    public AcceptanceAssertion {
        name = name == null ? "" : name.strip();
        target = target == null ? "" : target.strip();
        operator = operator == null ? "" : operator.strip();
        expected = expected == null ? "" : expected.strip();
    }
}
