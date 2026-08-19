package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.bootstrap.persistence.entity.AgentRemediationRoundRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentRemediationRoundMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskRemediationControllerTest {

    @Test
    void exposesKindsRoundsSourceTargetsCapacityAndHumanReason() throws Exception {
        AgentRemediationRoundMapper mapper = mock(AgentRemediationRoundMapper.class);
        AgentRemediationRoundRow product = row(11L, "QA_PRODUCT_FIX", 1, "DISPATCHED");
        product.targetCodingStageRunId = 21L;
        product.targetQaStageRunId = 22L;
        AgentRemediationRoundRow protocol = row(12L, "QA_PROTOCOL_RETRY", 1, "FAILED_NEEDS_HUMAN");
        protocol.targetQaStageRunId = 23L;
        when(mapper.listByTask(100L)).thenReturn(List.of(product, protocol));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RdTaskRemediationController(mapper)).build();

        mvc.perform(get("/admin/rd-tasks/100/remediations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacities[0].kind").value("QA_PRODUCT_FIX"))
                .andExpect(jsonPath("$.capacities[0].limit").value(2))
                .andExpect(jsonPath("$.capacities[0].remaining").value(1))
                .andExpect(jsonPath("$.capacities[1].kind").value("QA_PROTOCOL_RETRY"))
                .andExpect(jsonPath("$.capacities[1].remaining").value(0))
                .andExpect(jsonPath("$.rounds[0].sourceStageRunId").value("31"))
                .andExpect(jsonPath("$.rounds[0].targetCodingStageRunId").value("21"))
                .andExpect(jsonPath("$.rounds[1].manualReason").value("round requires human intervention"))
                .andExpect(jsonPath("$.manualReason").value(
                        "remediation QA_PROTOCOL_RETRY reached FAILED_NEEDS_HUMAN"));
    }

    @Test
    void rejectsNonNumericTaskIdentity() throws Exception {
        AgentRemediationRoundMapper mapper = mock(AgentRemediationRoundMapper.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RdTaskRemediationController(mapper)).build();
        mvc.perform(get("/admin/rd-tasks/not-a-number/remediations"))
                .andExpect(status().isBadRequest());
    }

    private static AgentRemediationRoundRow row(long id, String kind, int no, String status) {
        AgentRemediationRoundRow row = new AgentRemediationRoundRow();
        row.id = id;
        row.taskId = 100L;
        row.kind = kind;
        row.remediationNo = no;
        row.sourceStageRunId = 31L;
        row.firstCommandId = 41L;
        row.requestHash = "sha256:" + "a".repeat(64);
        row.status = status;
        row.createdAt = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        row.updatedAt = row.createdAt;
        return row;
    }
}
