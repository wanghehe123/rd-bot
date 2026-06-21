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
class QueryTermMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managesMappingsAndFeedsRagV3Rewrite() throws Exception {
        String created = mockMvc.perform(post("/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceTerm": "创建订单",
                                  "targetTerm": "POST /api/orders",
                                  "priority": 20,
                                  "enabled": true,
                                  "remark": "payment order api"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/mappings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTerm").value("创建订单"))
                .andExpect(jsonPath("$.targetTerm").value("POST /api/orders"));

        mockMvc.perform(get("/mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sourceTerm", hasItem("创建订单")));

        mockMvc.perform(get("/rag/v3/chat")
                        .param("question", "支付系统创建订单失败")
                        .param("conversationId", "conversation-query-term-test"))
                .andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                        .contains("event: meta")
                        .contains("POST /api/orders"));

        mockMvc.perform(put("/mappings/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceTerm": "创建订单接口",
                                  "targetTerm": "POST /api/orders",
                                  "priority": 30,
                                  "enabled": false,
                                  "remark": "disabled test"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mappings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTerm").value("创建订单接口"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete("/mappings/" + id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/mappings/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("not found")));
    }
}
