package com.wish.rd.rag.context;

import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;

class RoleContextBuilderTest {

    @Test
    void shouldBuildDifferentEvidenceForDifferentAgentRoles() {
        RoleContextBuilder builder = new RoleContextBuilder();
        RdRequirementTask task = RdRequirementTask.created(
                "task-1001",
                new CreateRequirementTaskCommand(
                        "增加订单催单能力",
                        "P1",
                        "https://github.com/example/waimai.git",
                        "example",
                        "waimai",
                        "main",
                        "订单详情页可以催单",
                        List.of("接口测试通过", "QA 验收通过"),
                        false
                ),
                1_783_000_000_000L
        );
        List<TaskMaterial> materials = List.of(
                material("mat-product", "产品需求", "用户场景、验收标准、产品边界。"),
                material("mat-architecture", "技术方案", "接口契约、数据库字段、架构影响。"),
                material("mat-code", "代码索引", "Controller 类、Service 方法、测试命令。"),
                material("mat-qa", "QA 日志", "测试用例、失败日志、验收记录。")
        );

        RoleContextPackage reviewer = builder.build("ctx-1", task, materials,
                "REQUIREMENT_REVIEWER", 8_000, 1_783_000_000_001L);
        RoleContextPackage architect = builder.build("ctx-2", task, materials,
                "SOLUTION_ARCHITECT", 8_000, 1_783_000_000_001L);
        RoleContextPackage coder = builder.build("ctx-3", task, materials,
                "CODING_AGENT", 8_000, 1_783_000_000_001L);
        RoleContextPackage qa = builder.build("ctx-4", task, materials,
                "QA_AGENT", 8_000, 1_783_000_000_001L);

        assertEquals("mat-product", reviewer.evidence().getFirst().evidenceId());
        assertEquals("mat-architecture", architect.evidence().getFirst().evidenceId());
        assertEquals("mat-code", coder.evidence().getFirst().evidenceId());
        assertEquals("mat-qa", qa.evidence().getFirst().evidenceId());
        assertEquals(List.of("接口测试通过", "QA 验收通过"), reviewer.acceptanceCriteria());
        assertTrue(reviewer.usedChars() <= reviewer.maxChars());
        assertTrue(reviewer.evidence().getFirst().contentHash().startsWith("sha256:"));
    }

    @Test
    void shouldRecordOmittedEvidenceWhenContextBudgetIsExceeded() {
        RoleContextBuilder builder = new RoleContextBuilder();
        RdRequirementTask task = RdRequirementTask.created(
                "task-1002",
                new CreateRequirementTaskCommand(
                        "增加订单催单能力",
                        "P1",
                        "https://github.com/example/waimai.git",
                        "example",
                        "waimai",
                        "main",
                        "订单详情页可以催单",
                        List.of("接口测试通过"),
                        false
                ),
                1_783_000_000_000L
        );

        RoleContextPackage context = builder.build("ctx-5", task, List.of(
                material("mat-1", "产品需求", "用户场景、验收标准、产品边界。"),
                material("mat-2", "产品补充", "更多用户场景、更多验收边界。")
        ), "REQUIREMENT_REVIEWER", 30, 1_783_000_000_001L);

        assertEquals(List.of("mat-2"), context.omittedEvidenceIds());
        assertEquals(1, context.evidence().size());
    }

    @Test
    void shouldBuildFromSelectedRetrievalEvidenceAndBindImmutableRun() {
        RoleContextBuilder builder = new RoleContextBuilder();
        RdRequirementTask task = RdRequirementTask.created(
                "task-1003",
                new CreateRequirementTaskCommand(
                        "增加订单催单能力", "P1", "https://github.com/example/waimai.git",
                        "example", "waimai", "main", "订单详情页可以催单",
                        List.of("接口测试通过"), false),
                1_783_000_000_000L);
        List<RoleContextEvidence> selected = List.of(
                new RoleContextEvidence(
                        "root", "TASK_INPUT", "rd-task://task-1003", "需求根证据", "sha256:root",
                        "订单详情页可以催单", 1_783_000_000_000L,
                        "required root evidence", 1.0d, "REQUIREMENT_ROOT", true),
                new RoleContextEvidence(
                        "code", "CODE", "code://OrderService.java#urge", "OrderService.urge", "sha256:code",
                        "催单服务方法与状态检查", 1_783_000_000_000L,
                        "matched coding role", 0.92d, "CODE_SYMBOL", false),
                new RoleContextEvidence(
                        "history-noise", "WORKFLOW_EXPERIENCE", "rd-experience://other", "优惠券经验",
                        "sha256:noise", "与催单无关的优惠券经验", 1_783_000_000_000L,
                        "below relevance threshold", 0.0d, "OPTIONAL_HISTORY", false)
        );

        RoleContextPackage context = builder.buildFromEvidence(
                "ctx-1003", task, selected, "CODING_AGENT", 8_000, 1, "run-1003",
                1_783_000_000_001L);

        assertEquals("run-1003", context.retrievalRunId());
        assertEquals(List.of("root", "code"), context.evidence().stream()
                .map(RoleContextEvidence::evidenceId).toList());
        assertFalse(context.evidence().stream()
                .anyMatch(evidence -> evidence.evidenceId().equals("history-noise")));
        assertTrue(context.omittedEvidenceIds().contains("history-noise"));
    }

    private TaskMaterial material(String id, String title, String preview) {
        return new TaskMaterial(
                id,
                "task-1001",
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                title,
                "https://example.test/" + id,
                "text/markdown",
                "sha256:" + id,
                preview,
                "",
                "",
                "rev-" + id,
                "{}",
                1_783_000_000_000L,
                1_783_000_000_000L
        );
    }
}
