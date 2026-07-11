package com.wish.rd.rag.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;
import com.wish.rd.rag.rewrite.model.QueryRewritePreview;
import com.wish.rd.rag.rewrite.model.QueryTermMappingCommand;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;
import com.wish.rd.rag.rewrite.model.RewriteResult;

class QueryTermMappingRegistryTest {

    @Test
    void managesMappingsAndBuildsRewriteServiceFromEnabledRules() {
        QueryTermMappingRegistry registry = QueryTermMappingRegistry.inMemory();

        ManagedQueryTermMapping mapping = registry.create(new QueryTermMappingCommand(
                "创建订单",
                "POST /api/orders",
                7,
                true,
                "payment order api"
        ));
        registry.create(new QueryTermMappingCommand(
                "禁用词",
                "SHOULD_NOT_APPEAR",
                99,
                false,
                "disabled"
        ));

        RewriteResult rewrite = registry.rewriteService()
                .rewriteWithSplit("支付系统创建订单失败。禁用词不会改写");

        assertTrue(rewrite.rewrittenQuestion().contains("POST /api/orders"));
        assertFalse(rewrite.rewrittenQuestion().contains("SHOULD_NOT_APPEAR"));
        assertEquals(mapping.id(), registry.get(mapping.id()).id());
        assertEquals(2, registry.list().size());
    }

    @Test
    void updatesAndDeletesMappings() {
        QueryTermMappingRegistry registry = QueryTermMappingRegistry.inMemory();
        ManagedQueryTermMapping mapping = registry.create(new QueryTermMappingCommand(
                "金额",
                "orders.amount",
                5,
                true,
                "amount field"
        ));

        registry.update(mapping.id(), new QueryTermMappingCommand(
                "订单金额",
                "orders.amount",
                9,
                true,
                "order amount field"
        ));

        assertEquals("订单金额", registry.get(mapping.id()).sourceTerm());
        assertEquals(9, registry.get(mapping.id()).priority());

        registry.delete(mapping.id());

        assertTrue(registry.list().isEmpty());
    }

    @Test
    void projectPreviewPrioritizesOwnRulesAndNeverLeaksAnotherProjectRules() {
        QueryTermMappingRegistry registry = QueryTermMappingRegistry.inMemory();
        ManagedQueryTermMapping globalOrder = registry.create(new QueryTermMappingCommand(
                "",
                QueryTermMappingScope.GLOBAL,
                "下单",
                "POST /global/orders",
                99,
                true,
                "global order endpoint"
        ));
        ManagedQueryTermMapping globalAmount = registry.create(new QueryTermMappingCommand(
                "",
                QueryTermMappingScope.GLOBAL,
                "金额",
                "orders.amount",
                9,
                true,
                "global amount field"
        ));
        ManagedQueryTermMapping projectOrder = registry.create(new QueryTermMappingCommand(
                "project-1",
                QueryTermMappingScope.PROJECT,
                "下单",
                "POST /p1/orders",
                1,
                true,
                "project one endpoint"
        ));
        registry.create(new QueryTermMappingCommand(
                "project-2",
                QueryTermMappingScope.PROJECT,
                "下单",
                "POST /p2/orders",
                1000,
                true,
                "must not leak"
        ));
        registry.create(new QueryTermMappingCommand(
                "",
                QueryTermMappingScope.GLOBAL,
                "错误",
                "SHOULD_NOT_APPEAR",
                100,
                false,
                "disabled"
        ));

        QueryRewritePreview preview = registry.preview("project-1", "支付下单金额错误");

        assertEquals("支付POST /p1/ordersorders.amount错误", preview.rewrittenText());
        assertEquals(preview.rewrittenText(), registry.rewriteService("project-1")
                .rewrite("支付下单金额错误"));
        assertEquals(List.of(projectOrder.id(), globalAmount.id()),
                preview.matches().stream().map(match -> match.mappingId()).toList());
        assertFalse(preview.rewrittenText().contains("POST /global/orders"));
        assertFalse(preview.rewrittenText().contains("POST /p2/orders"));
        assertFalse(preview.rewrittenText().contains("SHOULD_NOT_APPEAR"));
        assertEquals("支付POST /global/ordersorders.amount错误",
                registry.preview(null, "支付下单金额错误").rewrittenText());
        assertEquals("没有命中", registry.preview("project-1", "没有命中").rewrittenText());
        assertTrue(registry.preview("project-1", "没有命中").matches().isEmpty());
        assertEquals(QueryTermMappingScope.GLOBAL, globalOrder.scope());
    }

    @Test
    void filtersVisibleRulesAndRejectsInvalidScopeCombinations() {
        QueryTermMappingRegistry registry = QueryTermMappingRegistry.inMemory();
        ManagedQueryTermMapping global = registry.create(new QueryTermMappingCommand(
                "", QueryTermMappingScope.GLOBAL, "订单", "global-order", 1, true, "global"
        ));
        ManagedQueryTermMapping project = registry.create(new QueryTermMappingCommand(
                "project-1", QueryTermMappingScope.PROJECT, "订单", "project-order", 1, true, "project"
        ));
        registry.create(new QueryTermMappingCommand(
                "project-2", QueryTermMappingScope.PROJECT, "订单", "other-project-order", 1, true, "other"
        ));

        assertEquals(List.of(project.id()), registry.list("project-1", QueryTermMappingScope.PROJECT, true, "").stream()
                .map(ManagedQueryTermMapping::id)
                .toList());
        assertEquals(List.of(project.id(), global.id()), registry.list("project-1", null, true, "订单").stream()
                .map(ManagedQueryTermMapping::id)
                .toList());
        assertEquals(List.of(global.id()), registry.list(null, QueryTermMappingScope.GLOBAL, true, "").stream()
                .map(ManagedQueryTermMapping::id)
                .toList());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.create(new QueryTermMappingCommand(
                        "", QueryTermMappingScope.PROJECT, "source", "target", 1, true, ""
                )));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.create(new QueryTermMappingCommand(
                        "project-1", QueryTermMappingScope.GLOBAL, "source", "target", 1, true, ""
                )));
    }

    @Test
    void usesMappingIdAsStableTieBreakerForEquivalentRules() {
        QueryTermMappingRegistry registry = QueryTermMappingRegistry.inMemory();
        ManagedQueryTermMapping first = registry.create(new QueryTermMappingCommand(
                "", QueryTermMappingScope.GLOBAL, "订单", "first-order", 5, true, ""
        ));
        registry.create(new QueryTermMappingCommand(
                "", QueryTermMappingScope.GLOBAL, "订单", "second-order", 5, true, ""
        ));

        QueryRewritePreview preview = registry.preview("project-1", "订单失败");

        assertEquals("first-order失败", preview.rewrittenText());
        assertEquals(List.of(first.id()), preview.matches().stream().map(match -> match.mappingId()).toList());
    }

    @Test
    void keepsOriginalTextUntouchedWhenPreviewHasNoMatch() {
        QueryTermMappingRegistry registry = QueryTermMappingRegistry.inMemory();

        QueryRewritePreview preview = registry.preview("project-1", "  没有命中  ");

        assertEquals("  没有命中  ", preview.originalText());
        assertEquals("  没有命中  ", preview.rewrittenText());
        assertTrue(preview.matches().isEmpty());
    }
}
