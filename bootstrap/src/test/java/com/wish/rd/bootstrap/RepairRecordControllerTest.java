package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.controller.admin.repair.RepairRecordController;
import com.wish.rd.exec.repair.model.CreateRepairRecordArtifactCommand;
import com.wish.rd.exec.repair.model.CreateRepairAssetCommand;
import com.wish.rd.exec.repair.model.CreateRepairRecordCommand;
import com.wish.rd.exec.repair.impl.InMemoryRepairRecordRepository;
import com.wish.rd.exec.repair.model.RepairAssetType;
import com.wish.rd.exec.repair.RepairRecordRepository;
import com.wish.rd.exec.repair.model.RepairRecordStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 {@link RepairRecordController} 的分页查询、详情、产物列表与敏感字段脱敏。
 */
class RepairRecordControllerTest {

    private MockMvc mockMvc;
    private RepairRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository = InMemoryRepairRecordRepository.inMemory();
        mockMvc = MockMvcBuilders.standaloneSetup(new RepairRecordController(repository)).build();
    }

    @Test
    void listsRecordsWithFiltersAndPaging() throws Exception {
        String firstId = repository.create(new CreateRepairRecordCommand(
                "FS-A", "", "title-a", Map.of("priority", "P0")
        )).id();
        String secondId = repository.create(new CreateRepairRecordCommand(
                "FS-B", "", "title-b", Map.of("priority", "P1")
        )).id();
        repository.updateStatus(firstId, RepairRecordStatus.CONTEXT_READY, "summary-a");

        mockMvc.perform(get("/repair-records")
                        .param("status", "CONTEXT_READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].ticketId").value("FS-A"));

        mockMvc.perform(get("/repair-records")
                        .param("priority", "P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].ticketId").value("FS-B"));

        mockMvc.perform(get("/repair-records")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    @Test
    void returnsSingleRecordById() throws Exception {
        String id = repository.create(new CreateRepairRecordCommand(
                "FS-X", "https://ticket.example/X", "title-x", Map.of()
        )).id();

        mockMvc.perform(get("/repair-records/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.ticketId").value("FS-X"));
    }

    @Test
    void missingRecordReturns404() throws Exception {
        mockMvc.perform(get("/repair-records/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    @Test
    void listsArtifactsOrderedByCreation() throws Exception {
        String id = repository.create(new CreateRepairRecordCommand(
                "FS-Y", "", "title-y", Map.of()
        )).id();
        repository.addArtifact(new CreateRepairRecordArtifactCommand(
                id, "FEISHU_TICKET_SNAPSHOT", "", "snapshot"
        ));
        repository.addArtifact(new CreateRepairRecordArtifactCommand(
                id, "RAG_CONTEXT", "", "ctx"
        ));

        mockMvc.perform(get("/repair-records/" + id + "/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].artifactType").value("FEISHU_TICKET_SNAPSHOT"))
                .andExpect(jsonPath("$[1].artifactType").value("RAG_CONTEXT"));
    }

    @Test
    void listsAssetsOrderedByCreation() throws Exception {
        String firstRecordId = repository.create(new CreateRepairRecordCommand(
                "FS-ASSET-A", "", "title-a", Map.of()
        )).id();
        String secondRecordId = repository.create(new CreateRepairRecordCommand(
                "FS-ASSET-B", "", "title-b", Map.of()
        )).id();
        repository.addAsset(new CreateRepairAssetCommand(
                firstRecordId,
                RepairAssetType.BUG_CAUSE,
                "字段映射不一致",
                "client sends delivery_address while server requires address",
                "{\"field\":\"address\"}",
                "",
                true
        ));
        repository.addAsset(new CreateRepairAssetCommand(
                firstRecordId,
                RepairAssetType.ACCEPTANCE_PLAN,
                "外卖下单验收计划",
                "plan generated by docker claude planner",
                "{\"status\":\"READY\"}",
                "",
                false
        ));
        repository.addAsset(new CreateRepairAssetCommand(
                secondRecordId,
                RepairAssetType.BUG_CAUSE,
                "other",
                "other",
                "{}",
                "",
                true
        ));

        mockMvc.perform(get("/repair-records/" + firstRecordId + "/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].assetType").value("BUG_CAUSE"))
                .andExpect(jsonPath("$[0].title").value("字段映射不一致"))
                .andExpect(jsonPath("$[0].reusable").value(true))
                .andExpect(jsonPath("$[1].assetType").value("ACCEPTANCE_PLAN"));
    }

    @Test
    void redactsSensitiveExtensionKeysFromResponse() throws Exception {
        // extension_json 混入 helpdesk token，视图必须剔除
        String id = repository.create(new CreateRepairRecordCommand(
                "FS-SECRET", "", "title-secret", Map.of(
                        "helpdeskToken", "super-secret-token",
                        "priority", "P0",
                        "traceId", "trace-1"
                )
        )).id();

        String body = mockMvc.perform(get("/repair-records/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 断言敏感值不出现在响应体里
        if (body.contains("super-secret-token")) {
            throw new AssertionError("helpdesk token must be redacted from response: " + body);
        }
        mockMvc.perform(get("/repair-records/" + id))
                .andExpect(jsonPath("$.extensionJson.priority").value("P0"))
                .andExpect(jsonPath("$.extensionJson.traceId").value("trace-1"))
                .andExpect(jsonPath("$.extensionJson.helpdeskToken").doesNotExist());
    }
}
