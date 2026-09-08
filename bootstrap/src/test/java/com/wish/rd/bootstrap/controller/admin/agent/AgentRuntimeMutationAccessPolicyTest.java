package com.wish.rd.bootstrap.controller.admin.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 运行时修改令牌的 fail-closed 合同：未配置令牌必须 503 拒绝（服务降级为只读），
 * 配置后错误令牌 403、正确令牌放行。Docker 快速开始不得以固定公共 token 换取便利。
 */
class AgentRuntimeMutationAccessPolicyTest {

    @Test
    void emptyConfiguredTokenKeepsMutationsDenied() {
        AgentRuntimeMutationAccessPolicy policy = new AgentRuntimeMutationAccessPolicy("");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy.requireAuthorized("anything"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    void blankConfiguredTokenIsNormalizedToDenied() {
        AgentRuntimeMutationAccessPolicy policy = new AgentRuntimeMutationAccessPolicy("   ");

        assertThrows(ResponseStatusException.class, () -> policy.requireAuthorized(""));
    }

    @Test
    void wrongTokenIsForbidden() {
        AgentRuntimeMutationAccessPolicy policy = new AgentRuntimeMutationAccessPolicy("operator-secret");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policy.requireAuthorized("wrong"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void nullSuppliedTokenIsDeniedWhenConfigured() {
        AgentRuntimeMutationAccessPolicy policy = new AgentRuntimeMutationAccessPolicy("operator-secret");

        assertThrows(ResponseStatusException.class, () -> policy.requireAuthorized(null));
    }

    @Test
    void correctTokenPasses() {
        AgentRuntimeMutationAccessPolicy policy = new AgentRuntimeMutationAccessPolicy("operator-secret");

        assertDoesNotThrow(() -> policy.requireAuthorized("operator-secret"));
    }
}
