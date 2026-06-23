package com.wish.rd.bootstrap;

import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.bootstrap.feishu.im.FeishuImMessageController;
import com.wish.rd.bootstrap.feishu.im.FeishuImTicketAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证个人开发者可用的飞书 IM 工单入口可被 Spring 正确装配。
 *
 * <p>该测试不访问真实飞书，只验证 {@code rd.repair.ticket.provider=feishu-im}
 * 下端口唯一绑定到 IM 适配器，避免默认 mock 适配器与 IM provider 竞争。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rd.repair.ticket.provider=feishu-im",
                "rd.feishu.im.enabled=true",
                "rd.feishu.im.app-id=app-id-for-wiring-test",
                "rd.feishu.im.app-secret=app-secret-for-wiring-test"
        }
)
class FeishuImBeanWiringTest {

    @Autowired
    private FeishuImMessageController controller;
    @Autowired
    private TicketProviderPort providerPort;
    @Autowired
    private TicketUpdatePort updatePort;

    @Test
    void should_wire飞书IM工单端口_当启用IMProvider() {
        assertNotNull(controller);
        assertInstanceOf(FeishuImTicketAdapter.class, providerPort);
        assertInstanceOf(FeishuImTicketAdapter.class, updatePort);
    }
}
