package com.wish.rd.rag.project.template;

import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplateCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RdProjectTaskTemplateServiceTest {

    @Test
    void shouldSaveSeparateBugFixTemplate() {
        InMemoryStore store = new InMemoryStore();
        RdProjectTaskTemplateService service = new RdProjectTaskTemplateService(store);

        RdProjectTaskTemplate saved = service.update("7482000000000000401", "bug_fix", new RdProjectTaskTemplateCommand(
                "Web Bug", "页面报错", "接口返回 JSON", "1. 打开页面", "注册流程",
                List.of("注册成功", "接口返回 200"), "", ""
        ));

        assertEquals("BUG_FIX", saved.taskType());
        assertEquals(List.of("注册成功", "接口返回 200"), saved.acceptanceCriteria());
        assertEquals(saved, service.get(saved.projectId(), "BUG_FIX"));
    }

    @Test
    void shouldRejectUnsupportedTaskType() {
        RdProjectTaskTemplateService service = new RdProjectTaskTemplateService(new InMemoryStore());
        assertThrows(IllegalArgumentException.class, () -> service.get("7482000000000000402", "OTHER"));
    }

    private static final class InMemoryStore implements RdProjectTaskTemplateStore {
        private RdProjectTaskTemplate template;

        @Override
        public RdProjectTaskTemplate save(RdProjectTaskTemplate template) {
            this.template = template;
            return template;
        }

        @Override
        public Optional<RdProjectTaskTemplate> find(String projectId, String taskType) {
            return template == null || !template.projectId().equals(projectId) || !template.taskType().equals(taskType)
                    ? Optional.empty() : Optional.of(template);
        }
    }
}
