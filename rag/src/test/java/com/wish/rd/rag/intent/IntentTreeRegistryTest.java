package com.wish.rd.rag.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.intent.model.IntentNodeCommand;
import com.wish.rd.rag.intent.model.ManagedIntentNode;

class IntentTreeRegistryTest {

    @Test
    void managesTreeNodesAndBuildsEnabledIntentTree() {
        IntentTreeRegistry registry = IntentTreeRegistry.inMemory();

        ManagedIntentNode root = registry.create(new IntentNodeCommand(
                "refund-system",
                "退款系统",
                0,
                null,
                "退款、退单、refund",
                "refund-system",
                List.of("退款接口失败"),
                List.of("refund-service"),
                1,
                10
        ));
        ManagedIntentNode child = registry.create(new IntentNodeCommand(
                "refund-api",
                "退款接口",
                2,
                "refund-system",
                "退款提交接口",
                "refund-system",
                List.of("退款接口 500"),
                List.of("refund-service"),
                1,
                20
        ));

        assertEquals(1, registry.tree().size());
        assertEquals(child.intentCode(), registry.tree().getFirst().children().getFirst().intentCode());
        assertEquals(2, registry.intentTree().nodes().size());

        registry.batchDisable(List.of(root.id()));
        assertTrue(registry.intentTree().nodes().isEmpty());

        registry.batchEnable(List.of(root.id()));
        assertEquals("refund-system", registry.intentTree().roots().getFirst().id());
    }
}
