package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.draft.TaskDraftEngine;
import com.wish.rd.engine.draft.TaskDraftModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskDraftControllerTest {
    @Test
    void shouldReturnUnavailableWithoutCreatingFakeDraft() throws Exception {
        TaskDraftController controller = new TaskDraftController(new TaskDraftEngine(
                TaskDraftModelPort.unavailable("model credential is not configured")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/admin/rd-task-drafts/complete")
                        .contentType(APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "taskType", "BUG_FIX", "projectId", "", "currentValues", Map.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", is(false)))
                .andExpect(jsonPath("$.aiGenerated", is(false)))
                .andExpect(jsonPath("$.actualBehavior", is("")))
                .andExpect(jsonPath("$.reason", is("model credential is not configured")));
    }
}
