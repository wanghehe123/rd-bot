package com.wish.rd.bootstrap.controller.admin.trace;

import com.wish.rd.engine.admin.trace.ExecutionTracePage;
import com.wish.rd.engine.admin.trace.ExecutionTraceQuery;
import com.wish.rd.engine.admin.trace.ExecutionTraceQueryService;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project-scoped execution trace query endpoint for the delivery console. */
@RestController
public class ExecutionTraceController {

    private final ExecutionTraceQueryService queryService;

    @Autowired
    public ExecutionTraceController(
            RagStreamTaskRegistry taskRegistry,
            ObjectProvider<AgentStageRunStore> stageRunStoreProvider
    ) {
        this(new ExecutionTraceQueryService(taskRegistry, stageRunStoreProvider.getIfAvailable()));
    }

    public ExecutionTraceController(ExecutionTraceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/admin/execution-traces")
    public ExecutionTracePage list(
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "taskType", required = false) String taskType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return queryService.query(new ExecutionTraceQuery(
                projectId,
                taskType,
                status,
                role,
                provider,
                keyword,
                page,
                pageSize
        ));
    }
}
