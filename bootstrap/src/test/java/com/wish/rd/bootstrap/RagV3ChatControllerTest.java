package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagV3ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesRagentStyleSseChatEndpoint() throws Exception {
        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "支付系统下单接口 500。金额为空怎么修复？")
                        .param("deepThinking", "false"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(containsString("event: meta")))
                .andExpect(content().string(containsString("\"taskId\":\"task-")))
                .andExpect(content().string(containsString("event: delta")))
                .andExpect(content().string(containsString("OrderService.create")))
                .andExpect(content().string(containsString("event: done")));
    }

    @Test
    void exposesRagentStyleStopEndpoint() throws Exception {
        mockMvc.perform(post("/rag/v3/stop")
                        .param("taskId", "task-v3-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is("task-v3-test")))
                .andExpect(jsonPath("$.status", is("STOPPED")));
    }
}
