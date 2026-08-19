package com.wish.rd.rag.runtime.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁定 {@link RdTaskType#parse} 的失败关闭语义。
 *
 * <p>此前非法值会静默变成 {@code BUG_FIX}，于是一个拼错的类型会被派到工单链——而工单链已下线。
 */
class RdTaskTypeTest {

    @Test
    void shouldRejectAnUnknownTypeInsteadOfSilentlyFallingBack() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> RdTaskType.parse("BUGFIX", RdTaskType.REQUIREMENT));

        assertEquals("unknown rd task type: BUGFIX", failure.getMessage());
    }

    @Test
    void shouldUseRequirementWhenNoFallbackIsGivenForABlankValue() {
        assertEquals(RdTaskType.REQUIREMENT, RdTaskType.parse("", null));
        assertEquals(RdTaskType.REQUIREMENT, RdTaskType.parse(null, null));
        assertEquals(RdTaskType.REQUIREMENT, RdTaskType.parse("   ", null));
    }

    @Test
    void shouldHonorTheCallerFallbackForABlankValue() {
        assertEquals(RdTaskType.BUG_FIX, RdTaskType.parse("", RdTaskType.BUG_FIX));
    }

    @Test
    void shouldStillParseHistoricalTypeNames() {
        assertEquals(RdTaskType.BUG_FIX, RdTaskType.parse("bug_fix", null));
        assertEquals(RdTaskType.REQUIREMENT, RdTaskType.parse(" requirement ", null));
        assertEquals(RdTaskType.QNA, RdTaskType.parse("QNA", null));
    }
}
