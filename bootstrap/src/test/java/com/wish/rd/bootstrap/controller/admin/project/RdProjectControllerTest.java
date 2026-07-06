package com.wish.rd.bootstrap.controller.admin.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.model.RdProjectCommand;
import com.wish.rd.rag.project.RdProjectService;
import com.wish.rd.rag.project.RdProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RdProjectController} 项目管理接口单测。
 */
class RdProjectControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RdProjectService service = new RdProjectService(generator(), new FakeRdProjectStore());
        mockMvc = MockMvcBuilders.standaloneSetup(new RdProjectController(service)).build();
    }

    @Test
    void shouldCreateListUpdateAndDeleteProject() throws Exception {
        String createBody = objectMapper.writeValueAsString(Map.of(
                "projectKey", "waimai",
                "name", "外卖系统",
                "description", "外卖订单验收仓库",
                "repositoryUrl", "https://github.com/example/waimai.git",
                "defaultBranch", "main",
                "enabled", true
        ));

        String response = mockMvc.perform(post("/admin/projects")
                        .contentType(APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectKey", is("waimai")))
                .andExpect(jsonPath("$.repoOwner", is("example")))
                .andExpect(jsonPath("$.repoName", is("waimai")))
                .andReturn().getResponse().getContentAsString();
        String projectId = com.jayway.jsonpath.JsonPath.read(response, "$.projectId");

        mockMvc.perform(get("/admin/projects").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].projectId", is(projectId)));

        mockMvc.perform(put("/admin/projects/{projectId}", projectId)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectKey", "waimai",
                                "name", "外卖系统 Pro",
                                "description", "更新描述",
                                "repositoryUrl", "https://github.com/example/waimai.git",
                                "defaultBranch", "develop",
                                "enabled", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("外卖系统 Pro")))
                .andExpect(jsonPath("$.defaultBranch", is("develop")));

        mockMvc.perform(delete("/admin/projects/{projectId}", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        mockMvc.perform(get("/admin/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));
    }

    @Test
    void shouldRejectBlankRepositoryUrl() throws Exception {
        mockMvc.perform(post("/admin/projects")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "projectKey", "waimai",
                                "name", "外卖系统",
                                "repositoryUrl", ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("repositoryUrl")));
    }

    private static SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_100_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private static final class FakeRdProjectStore implements RdProjectStore {

        private final LinkedHashMap<String, RdProject> projects = new LinkedHashMap<>();

        @Override
        public RdProject save(RdProject project) {
            projects.put(project.projectId(), project);
            return project;
        }

        @Override
        public Optional<RdProject> findById(String projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public Optional<RdProject> findByKey(String projectKey) {
            return projects.values().stream()
                    .filter(project -> project.projectKey().equals(projectKey))
                    .findFirst();
        }

        @Override
        public List<RdProject> list() {
            return List.copyOf(projects.values());
        }
    }
}
