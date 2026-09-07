package com.wish.rd.engine.requirement.manager;

import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorMaterialNeedDetectorTest {

    @Test
    void detectsExplicitNeedUserInputMarkerInMaterialPreview() {
        assertTrue(OperatorMaterialNeedDetector.requiresOperatorInput(
                List.of(material("req", "P3-W2-ASK-MARKER：故意省略关键页面路径")),
                "build green",
                List.of("AC one")));
    }

    @Test
    void detectsIntentionalUnprovidedMaterial() {
        assertTrue(OperatorMaterialNeedDetector.requiresOperatorInput(
                List.of(material("req", "关键上线窗口故意未提供")),
                "build green",
                List.of("AC one")));
    }

    @Test
    void ignoresOrdinaryMaterials() {
        assertFalse(OperatorMaterialNeedDetector.requiresOperatorInput(
                List.of(material("req", "实现顾客首页静态标记 P3-W1G-HOMEPAGE")),
                "客户首页可见标记",
                List.of("构建通过", "首页标记")));
    }

    private static TaskMaterial material(String title, String preview) {
        return new TaskMaterial(
                "mat-1",
                "task-1",
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                title,
                "",
                "text/plain",
                "sha256:abc",
                preview,
                "",
                "",
                "",
                "{}",
                1L,
                1L);
    }
}
