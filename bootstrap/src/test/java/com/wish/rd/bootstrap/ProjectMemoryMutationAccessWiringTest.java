package com.wish.rd.bootstrap;

import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAction;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationAuthorizer;
import com.wish.rd.engine.admin.projectmemory.ProjectMemoryMutationDeniedException;
import com.wish.rd.engine.admin.projectmemory.TrustedOperatorPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T09/W1 合同：项目记忆治理 authorizer 必须在任何 store 模式下都可装配（memory 模式的
 * context 也要能启动），且未配置可信 operator 时一律 fail closed 拒绝。
 * 首发不支持 memory 路径，但拒绝行为不允许因为 wiring 条件而漂移。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"rd.knowledge.store=memory"}
)
class ProjectMemoryMutationAccessWiringTest {

    @Autowired
    private ProjectMemoryMutationAuthorizer authorizer;

    @Test
    void memoryStoreContextStillWiresTheFailClosedAuthorizer() {
        assertNotNull(authorizer);
        assertThrows(ProjectMemoryMutationDeniedException.class, () ->
                authorizer.authorize(null, "project-1", ProjectMemoryMutationAction.CONFIRM, "req-1"));
    }

    @Test
    void anonymousOperatorWithoutCapabilitiesIsDenied() {
        TrustedOperatorPrincipal anonymous = new TrustedOperatorPrincipal(
                "operator-1", java.util.Set.of(), java.util.Set.of("project-1"));

        assertThrows(ProjectMemoryMutationDeniedException.class, () ->
                authorizer.authorize(anonymous, "project-1", ProjectMemoryMutationAction.CONFIRM, "req-1"));
    }
}
