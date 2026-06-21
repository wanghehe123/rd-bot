package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SampleQuestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managesSampleQuestionsAndExposesWelcomeList() throws Exception {
        String created = mockMvc.perform(post("/sample-questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "支付排障",
                                  "description": "订单金额为空",
                                  "question": "支付系统下单接口 500，金额为空怎么修复？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andExpect(jsonPath("$.question").value("支付系统下单接口 500，金额为空怎么修复？"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/sample-questions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("支付排障"));

        mockMvc.perform(get("/sample-questions")
                        .param("keyword", "金额")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[*].id", hasItem(id)));

        mockMvc.perform(get("/rag/sample-questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].question", hasItem("支付系统下单接口 500，金额为空怎么修复？")));

        mockMvc.perform(put("/sample-questions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "支付接口排障",
                                  "question": "支付系统创建订单失败如何修复？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("支付接口排障"))
                .andExpect(jsonPath("$.description").value("订单金额为空"))
                .andExpect(jsonPath("$.question").value("支付系统创建订单失败如何修复？"));

        mockMvc.perform(delete("/sample-questions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/sample-questions/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("not found")));
    }
}
