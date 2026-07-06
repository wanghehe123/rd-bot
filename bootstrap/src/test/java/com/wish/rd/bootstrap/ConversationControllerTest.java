package com.wish.rd.bootstrap;

import com.wish.rd.framework.convention.model.ChatMessage;
import com.wish.rd.rag.memory.impl.ConversationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationRegistry conversationRegistry;

    @Test
    void exposesConversationListAndMessages() throws Exception {
        String conversationId = "conversation-management-test";
        conversationRegistry.append(
                conversationId,
                "test-user",
                ChatMessage.user("支付系统下单接口 500。金额为空怎么修复？")
        );
        conversationRegistry.append(
                conversationId,
                "test-user",
                ChatMessage.assistant("已经定位到 OrderService.create 缺少 orders.amount 校验。")
        );

        mockMvc.perform(get("/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].conversationId", hasItem(conversationId)));

        mockMvc.perform(get("/conversations/" + conversationId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("支付系统下单接口 500。金额为空怎么修复？"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content", containsString("OrderService.create")));

        mockMvc.perform(put("/conversations/" + conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"支付接口排障"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("支付接口排障"));

        mockMvc.perform(delete("/conversations/" + conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].conversationId", not(hasItem(conversationId))));
    }
}
