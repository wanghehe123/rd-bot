package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;

/**
 * {@link TaskMaterialStore} 单测。
 *
 * <p>需求文档、手填正文和 Feishu 文档应作为任务材料独立保存，不写入 prompt 快照字段。
 */
class TaskMaterialStoreTest {

    @Test
    void shouldSaveListFindAndDeleteTaskMaterials() {
        TaskMaterialStore store = new InMemoryTaskMaterialStore();
        TaskMaterial material = new TaskMaterial(
                "mat-1",
                "task-1",
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求说明.md",
                "",
                "text/markdown",
                "sha256:abc",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{\"source\":\"admin\"}",
                1_782_000_000_000L,
                1_782_000_000_000L
        );

        TaskMaterial saved = store.save(material);

        assertEquals(material, saved);
        assertEquals(List.of(saved), store.listByTask("task-1"));
        assertEquals(saved, store.findById("mat-1").orElseThrow());
        assertEquals(1, store.deleteByTask("task-1"));
        assertTrue(store.listByTask("task-1").isEmpty());
    }
}
