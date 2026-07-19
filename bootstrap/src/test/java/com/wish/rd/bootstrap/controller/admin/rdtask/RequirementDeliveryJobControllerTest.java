package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequirementDeliveryJobControllerTest {

    @Test
    void shouldExposeDurableDispatchStateByTask() throws Exception {
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-1", 3, 100L));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RequirementDeliveryJobController(store)).build();

        mockMvc.perform(get("/admin/rd-tasks/task-1/delivery-job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void shouldReturnNotFoundJsonWhenDeliveryJobMissing() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RequirementDeliveryJobController(new InMemoryRequirementDeliveryJobStore())).build();

        mockMvc.perform(get("/admin/rd-tasks/missing/delivery-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("requirement delivery job not found: missing"));
    }
}
