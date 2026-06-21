package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagV3ChatRuntimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void recordsTaskStateForChatAndStopRequests() throws Exception {
        String body = mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "支付系统下单接口 500。金额为空怎么修复？"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: done")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String taskId = body.replaceFirst("(?s).*\\\"taskId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/rag/v3/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(not(emptyString())))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        mockMvc.perform(post("/rag/v3/stop")
                        .param("taskId", "task-cancel-before-register"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-cancel-before-register"))
                .andExpect(jsonPath("$.status").value("STOPPED"));

        mockMvc.perform(get("/rag/v3/tasks/task-cancel-before-register"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
