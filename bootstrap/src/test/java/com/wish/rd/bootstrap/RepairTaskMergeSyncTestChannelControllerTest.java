package com.wish.rd.bootstrap;

import com.wish.rd.engine.merge.model.PullRequestMergeStatus;
import com.wish.rd.engine.merge.PullRequestMergeStatusPort;
import com.wish.rd.engine.merge.RepairTaskMergeSyncEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepairTaskMergeSyncTestChannelControllerTest {

    @Test
    void shouldImportCommittedTaskAndSyncMergedPullRequest() throws Exception {
        RagStreamTaskRegistry registry = registry();
        RepairTaskMergeSyncEngine engine = new RepairTaskMergeSyncEngine(
                registry,
                new StaticPullRequestMergeStatusPort(true)
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new com.wish.rd.bootstrap.controller.testchannel.RepairTaskMergeSyncTestChannelController(
                        registry,
                        engine
                ))
                .build();

        mockMvc.perform(post("/test/repair/bugfix/tasks/7475111648697651200/committed")
                        .contentType("application/json")
                        .content("""
                                {
                                  "ticketId": "FI-real-waimai-20260623",
                                  "title": "外卖下单接口返回 500",
                                  "priority": "P1",
                                  "pullRequestUrl": "https://github.com/acme/order/pull/42"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("7475111648697651200"))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.pullRequestUrl").value("https://github.com/acme/order/pull/42"));

        mockMvc.perform(post("/test/repair/bugfix/tasks/7475111648697651200/sync-merge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("7475111648697651200"))
                .andExpect(jsonPath("$.status").value("MERGED"));
    }

    private static RagStreamTaskRegistry registry() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement)
        );
    }

    private static final class StaticPullRequestMergeStatusPort implements PullRequestMergeStatusPort {

        private final boolean merged;

        private StaticPullRequestMergeStatusPort(boolean merged) {
            this.merged = merged;
        }

        @Override
        public PullRequestMergeStatus findByUrl(String pullRequestUrl) {
            return new PullRequestMergeStatus(pullRequestUrl, "acme/order", "42", merged ? "closed" : "open", merged);
        }
    }
}
