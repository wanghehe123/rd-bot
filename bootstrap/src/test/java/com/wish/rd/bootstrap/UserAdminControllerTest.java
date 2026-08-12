package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
class UserAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managesUsersWithAdminEndpoints() throws Exception {
        mockMvc.perform(get("/users")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[*].username", hasItem("admin")))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)));

        String created = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "operator",
                                  "password": "secret-123",
                                  "role": "user",
                                  "avatar": "https://example.com/avatar.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", not(blankOrNullString())))
                .andExpect(jsonPath("$.username").value("operator"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/users")
                        .param("current", "1")
                        .param("size", "10")
                        .param("keyword", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value(id))
                .andExpect(jsonPath("$.records[0].role").value("user"));

        mockMvc.perform(put("/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "operator-admin",
                                  "role": "admin",
                                  "password": "secret-456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator-admin"))
                .andExpect(jsonPath("$.role").value("admin"));

        mockMvc.perform(delete("/users/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }
}
