package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;

class RequirementDeliveryEngineInjectionTest {

    @Test
    void shouldUseInjectedStageRunStoreFromSpringConstructor() {
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        InMemoryTaskMaterialStore materialStore = new InMemoryTaskMaterialStore();
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "增加订单催单功能",
                "P1",
                "https://github.com/example/waimai.git",
                "example",
                "waimai",
                "main",
                "订单详情页可以催单",
                List.of("接口测试通过"),
                false
        ));
        materialStore.save(new TaskMaterial(
                "7478000000001000001",
                task.taskId(),
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求正文",
                "",
                "text/markdown",
                "sha256:test",
                "用户可以在订单详情页点击催单。",
                "",
                "",
                "",
                "{}",
                1L,
                1L
        ));
        AgentStageRunStore stageRunStore = new InMemoryAgentStageRunStore();
        RoleContextPackageStore contextPackageStore = new InMemoryRoleContextPackageStore();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("executor", successfulExecutor());
        beans.addBean("stageRunStore", stageRunStore);
        beans.addBean("contextPackageStore", contextPackageStore);

        RequirementDeliveryEngine engine = new RequirementDeliveryEngine(
                registry,
                materialStore,
                beans.getBeanProvider(RequirementExecutorPort.class),
                beans.getBeanProvider(AgentStageRunStore.class),
                beans.getBeanProvider(RoleContextPackageStore.class),
                generator()
        );

        engine.submit(task.taskId());

        assertEquals(4, stageRunStore.listByTask(task.taskId()).size());
        assertEquals(List.of(
                "REQUIREMENT_REVIEWER",
                "SOLUTION_ARCHITECT",
                "CODING_AGENT",
                "QA_AGENT"
        ), contextPackageStore.listByTask(task.taskId()).stream().map(RoleContextPackage::role).toList());
    }

    private RequirementExecutorPort successfulExecutor() {
        return request -> RequirementExecutionResult.success(
                request.taskId(),
                "实现完成",
                "https://github.com/example/waimai/pull/12",
                "{\"status\":\"SUCCESS\"}"
        );
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }
}
