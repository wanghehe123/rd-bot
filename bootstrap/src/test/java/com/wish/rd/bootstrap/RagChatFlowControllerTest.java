package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagChatFlowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesTwoTurnChatFlowWithConversationMemory() throws Exception {
        mockMvc.perform(post("/test/rag/chat-flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId", is("conversation-memory-test")))
                .andExpect(jsonPath("$.firstAnswer", containsString("OrderService.create")))
                .andExpect(jsonPath("$.secondPromptSections", hasItem("对话记忆")))
                .andExpect(jsonPath("$.secondUserPrompt", containsString("【对话记忆】")))
                .andExpect(jsonPath("$.secondUserPrompt", containsString("支付系统下单接口 500")))
                .andExpect(jsonPath("$.secondUserPrompt", containsString("orders.amount 校验")))
                .andExpect(jsonPath("$.historyMessageCount", is(4)));
    }
}
