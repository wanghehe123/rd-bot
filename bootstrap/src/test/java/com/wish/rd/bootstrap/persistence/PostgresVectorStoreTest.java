package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresVectorStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.KnowledgeVectorRow;
import com.wish.rd.bootstrap.persistence.mapper.KnowledgeVectorMapper;
import com.wish.rd.framework.convention.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresVectorStoreTest {

    @Test
    void shouldIndexAndRemoveNonNumericChunkIds() {
        KnowledgeVectorMapper mapper = inMemoryMapper();
        PostgresVectorStore store = new PostgresVectorStore(mapper, new ObjectMapper(), 8);
        RetrievedChunk chunk = new RetrievedChunk(
                "payment-api.md#0",
                "POST /api/orders creates an order",
                "payment-system",
                "api",
                "payment-api.md",
                0.0d,
                Map.of("chunkIndex", "0")
        );

        assertDoesNotThrow(() -> store.index(List.of(chunk)));
        assertEquals("payment-api.md#0", store.allChunks().getFirst().chunkId());

        store.removeChunks(List.of("payment-api.md#0"));
        assertTrue(store.allChunks().isEmpty());
    }

    private KnowledgeVectorMapper inMemoryMapper() {
        InvocationHandler handler = new InvocationHandler() {
            private final Map<Long, KnowledgeVectorRow> rows = new LinkedHashMap<>();

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "upsert" -> {
                        KnowledgeVectorRow row = (KnowledgeVectorRow) args[0];
                        rows.put(row.id, row);
                        yield null;
                    }
                    case "selectAllForRetrieval" -> new ArrayList<>(rows.values());
                    case "deleteBatchIds" -> {
                        Collection<?> ids = (Collection<?>) args[0];
                        ids.forEach(id -> rows.remove((Long) id));
                        yield 0;
                    }
                    case "toString" -> "InMemoryKnowledgeVectorMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unexpected mapper call: " + method.getName());
                };
            }
        };
        return (KnowledgeVectorMapper) Proxy.newProxyInstance(
                KnowledgeVectorMapper.class.getClassLoader(),
                new Class<?>[]{KnowledgeVectorMapper.class},
                handler
        );
    }
}
