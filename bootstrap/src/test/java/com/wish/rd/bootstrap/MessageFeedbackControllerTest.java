package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MessageFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submitsAndUpsertsAssistantMessageFeedback() throws Exception {
        String conversationId = "conversation-feedback-test";
        String assistantMessageId = conversationId + "#2";

        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "支付系统下单接口 500。金额为空怎么修复？")
                        .param("conversationId", conversationId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: done")));

        mockMvc.perform(post("/conversations/messages/{messageId}/feedback", assistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vote":1,"reason":"helpful","comment":"定位准确"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(assistantMessageId))
                .andExpect(jsonPath("$.vote").value(1))
                .andExpect(jsonPath("$.reason").value("helpful"));

        mockMvc.perform(post("/conversations/messages/{messageId}/feedback", assistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vote":-1,"reason":"missing detail","comment":"缺少代码位置"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vote").value(-1))
                .andExpect(jsonPath("$.comment").value("缺少代码位置"));

        mockMvc.perform(get("/conversations/" + conversationId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(assistantMessageId)))
                .andExpect(jsonPath("$[1].vote").value(-1));

        mockMvc.perform(post("/conversations/messages/{messageId}/feedback", assistantMessageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vote":0,"reason":"invalid"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("vote must be 1 or -1")));
    }
}
