package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.wish.rd.rag.project.RdProjectService;

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
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class QueryTermMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RdProjectService projectService;

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

    @Test
    void filtersProjectRulesAndPreviewsOnlyEffectiveScope() throws Exception {
        mockMvc.perform(post("/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "7482000000000000201",
                                  "scope": "PROJECT",
                                  "sourceTerm": "项目专属下单",
                                  "targetTerm": "POST /p1/orders",
                                  "priority": 1,
                                  "enabled": true,
                                  "remark": "project scope"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PROJECT"));

        mockMvc.perform(get("/mappings")
                        .param("projectId", "7482000000000000201")
                        .param("scope", "PROJECT")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scope").value("PROJECT"))
                .andExpect(jsonPath("$[0].projectId").value("7482000000000000201"));

        mockMvc.perform(post("/mappings/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"7482000000000000201","text":"项目专属下单失败"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").value("项目专属下单失败"))
                .andExpect(jsonPath("$.rewrittenText").value("POST /p1/orders失败"))
                .andExpect(jsonPath("$.matches[0].scope").value("PROJECT"));

        mockMvc.perform(post("/mappings/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"7482000000000000201\",\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("text")));

        mockMvc.perform(post("/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "7482000000000000201",
                                  "scope": "GLOBAL",
                                  "sourceTerm": "invalid",
                                  "targetTerm": "invalid",
                                  "priority": 1,
                                  "enabled": true,
                                  "remark": "invalid scope"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("projectId")));
    }

    @Test
    void rejectsMalformedProjectAndMappingIdsAsBadRequests() throws Exception {
        mockMvc.perform(post("/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "not-a-project-id",
                                  "scope": "PROJECT",
                                  "sourceTerm": "订单",
                                  "targetTerm": "POST /orders",
                                  "priority": 1,
                                  "enabled": true,
                                  "remark": "invalid project id"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("projectId")));

        mockMvc.perform(get("/mappings/not-a-mapping-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("id")));

        mockMvc.perform(delete("/mappings/not-a-mapping-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("id")));
    }

    @Test
    void returnsNotFoundWhenProjectRuleReferencesAnUnknownProject() throws Exception {
        when(projectService.getEnabled("7482000000000000999"))
                .thenThrow(new java.util.NoSuchElementException("project not found: 7482000000000000999"));

        mockMvc.perform(post("/mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "7482000000000000999",
                                  "scope": "PROJECT",
                                  "sourceTerm": "订单",
                                  "targetTerm": "POST /orders",
                                  "priority": 1,
                                  "enabled": true,
                                  "remark": "missing project"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("project not found")));
    }
}
