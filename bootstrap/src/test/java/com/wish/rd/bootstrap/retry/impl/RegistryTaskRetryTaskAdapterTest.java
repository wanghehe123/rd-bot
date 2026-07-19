package com.wish.rd.bootstrap.retry.impl;

import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistryTaskRetryTaskAdapterTest {

    @Test
    void restoresRecoveringTaskToFailedRetryableWithoutDroppingExecutionResult() {
        RagStreamTaskRegistry registry = mock(RagStreamTaskRegistry.class);
        RdRequirementTask recovering = task(RdTaskStatus.RECOVERING, "{\"phase\":\"coding\"}");
        RdRequirementTask failed = task(RdTaskStatus.FAILED_RETRYABLE, recovering.executionResultJson());
        when(registry.getTask("task-1")).thenReturn(recovering);
        when(registry.markRequirementFailedRetryable(
                "task-1", "queue unavailable", recovering.executionResultJson())).thenReturn(failed);
        RegistryTaskRetryTaskAdapter adapter = new RegistryTaskRetryTaskAdapter(registry);

        RdRequirementTask result = adapter.markRetryPreparationFailed("task-1", "queue unavailable");

        assertSame(failed, result);
        verify(registry).markRequirementFailedRetryable(
                "task-1", "queue unavailable", recovering.executionResultJson());
    }

    private static RdRequirementTask task(RdTaskStatus status, String executionResultJson) {
        return new RdRequirementTask(
                "task-1", "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", "project-1", "waimai", "外卖项目", "https://github.example/waimai",
                "owner", "repo", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", executionResultJson, "", "failed", 10L, 300L, false
        );
    }
}
