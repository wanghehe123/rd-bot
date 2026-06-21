package com.wish.rd.rag.rewrite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
