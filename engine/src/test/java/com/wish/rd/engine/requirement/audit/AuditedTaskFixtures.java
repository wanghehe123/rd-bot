package com.wish.rd.engine.requirement.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

final class AuditedTaskFixtures {

    private AuditedTaskFixtures() {
    }

    static RdRequirementTask task(String acceptanceCriteriaJson, boolean withBundle) {
        ObjectNode bundle = null;
        if (withBundle) {
            bundle = new ObjectMapper().createObjectNode();
            bundle.putArray("assertions").addObject().put("id", "assert-1");
        }
        return new RdRequirementTask(
                "task-1",
                "REQUIREMENT",
                "ADMIN",
                "",
                "",
                "P1",
                RdTaskStatus.EXECUTING,
                "Test",
                "project-1",
                "project",
                "Project",
                "https://github.com/example/app.git",
                "example",
                "app",
                "main",
                "requirement/task-1",
                "expected",
                acceptanceCriteriaJson,
                "",
                "{}",
                "",
                "",
                1L,
                1L,
                false,
                0L,
                2L,
                7L,
                bundle
        );
    }
}
