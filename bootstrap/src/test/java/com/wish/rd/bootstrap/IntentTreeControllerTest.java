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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IntentTreeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managesIntentTreeAndFeedsRagV3Classifier() throws Exception {
        String created = mockMvc.perform(post("/intent-tree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "intentCode": "refund-system-test",
                                  "name": "退款系统",
                                  "level": 0,
                                  "description": "退款 退单 refund",
                                  "kbId": "refund-system-test",
                                  "examples": ["退款接口失败"],
                                  "enabled": 1,
                                  "sortOrder": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andExpect(jsonPath("$.intentCode").value("refund-system-test"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/intent-tree/trees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].intentCode", hasItem("refund-system-test")));

        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "退款系统退款接口失败")
                        .param("conversationId", "conversation-intent-tree-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: meta")))
                .andExpect(content().string(containsString("\"intentSystemId\":\"refund-system-test\"")));

        mockMvc.perform(put("/intent-tree/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "退款接口域",
                                  "enabled": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("退款接口域"))
                .andExpect(jsonPath("$.enabled").value(0));

        mockMvc.perform(post("/intent-tree/batch/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":["%s"]}
                                """.formatted(id)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/intent-tree/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }
}
