package com.wish.rd.bootstrap.controller.admin.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.template.RdProjectTaskTemplateService;
import com.wish.rd.rag.project.template.RdProjectTaskTemplateStore;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdProjectTaskTemplateControllerTest {

    @Test
    void shouldSaveAndReadBugFixTemplate() throws Exception {
        RdProjectTaskTemplateService service = new RdProjectTaskTemplateService(new Store());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RdProjectTaskTemplateController(service)).build();
        String projectId = "7482000000000000501";
        String body = new ObjectMapper().writeValueAsString(Map.of(
                "name", "前端 Bug",
                "actualBehavior", "页面报错",
                "expectedBehavior", "正常返回",
                "reproductionSteps", "1. 打开注册页",
                "affectedScope", "注册",
                "acceptanceCriteria", java.util.List.of("HTTP 200", "页面成功")
        ));

        mvc.perform(put("/admin/projects/{projectId}/task-templates/BUG_FIX", projectId)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("前端 Bug")))
                .andExpect(jsonPath("$.acceptanceCriteria", hasSize(2)));

        mvc.perform(get("/admin/projects/{projectId}/task-templates/BUG_FIX", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualBehavior", is("页面报错")));
    }

    private static final class Store implements RdProjectTaskTemplateStore {
        private RdProjectTaskTemplate template;

        public RdProjectTaskTemplate save(RdProjectTaskTemplate template) { this.template = template; return template; }
        public Optional<RdProjectTaskTemplate> find(String projectId, String taskType) {
            return template == null ? Optional.empty() : Optional.of(template);
        }
    }
}
