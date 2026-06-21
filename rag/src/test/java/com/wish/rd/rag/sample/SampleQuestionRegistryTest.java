package com.wish.rd.rag.sample;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleQuestionRegistryTest {

    @Test
    void createsUpdatesSearchesAndDeletesSampleQuestions() {
        SampleQuestionRegistry registry = SampleQuestionRegistry.inMemory();

        ManagedSampleQuestion first = registry.create(new SampleQuestionCommand(
                "支付排障",
                "订单金额为空",
                "支付系统下单接口 500，金额为空怎么修复？"
        ));
        registry.create(new SampleQuestionCommand(
                "登录排障",
                "会话失效",
                "登录系统 token 过期后如何恢复？"
        ));

        SampleQuestionPage paymentPage = registry.page("金额", 1, 10);
        assertEquals(1, paymentPage.total());
        assertEquals(first.id(), paymentPage.items().getFirst().id());

        ManagedSampleQuestion updated = registry.update(first.id(), new SampleQuestionCommand(
                "支付接口排障",
                null,
                "支付系统创建订单失败如何修复？"
        ));
        assertEquals("支付接口排障", updated.title());
        assertEquals("订单金额为空", updated.description());
        assertEquals("支付系统创建订单失败如何修复？", updated.question());

        registry.delete(first.id());

        assertThrows(NoSuchElementException.class, () -> registry.get(first.id()));
    }

    @Test
    void welcomeQuestionsReturnAtMostThreeEnabledRecords() {
        SampleQuestionRegistry registry = SampleQuestionRegistry.inMemory();

        registry.create(new SampleQuestionCommand("A", "alpha", "question A"));
        registry.create(new SampleQuestionCommand("B", "beta", "question B"));
        registry.create(new SampleQuestionCommand("C", "gamma", "question C"));
        registry.create(new SampleQuestionCommand("D", "delta", "question D"));

        assertEquals(3, registry.listWelcomeQuestions().size());
        assertTrue(registry.listWelcomeQuestions().stream()
                .allMatch(question -> question.question().startsWith("question ")));
    }
}
