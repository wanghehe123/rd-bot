package com.wish.rd.bootstrap;

import com.wish.rd.adapter.TicketProviderPort;
import com.wish.rd.adapter.TicketUpdatePort;
import com.wish.rd.bootstrap.feishu.ticket.FeishuHelpdeskClient;
import com.wish.rd.bootstrap.feishu.ticket.FeishuTicketAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证真实飞书 Helpdesk Bean 在启用配置下可被 Spring 装配。
 *
 * <p>该测试不调用外部飞书接口，只覆盖构造器选择与端口绑定，避免真实凭据门控测试
 * 才能发现 {@code rd.feishu.helpdesk.enabled=true} 下的启动问题。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rd.feishu.helpdesk.enabled=true",
                "rd.feishu.helpdesk.app-id=app-id-for-wiring-test",
                "rd.feishu.helpdesk.app-secret=app-secret-for-wiring-test",
                "rd.feishu.helpdesk.helpdesk-id=helpdesk-id-for-wiring-test",
                "rd.feishu.helpdesk.helpdesk-token=helpdesk-token-for-wiring-test"
        }
)
class FeishuHelpdeskBeanWiringTest {

    @Autowired
    private FeishuHelpdeskClient client;
    @Autowired
    private TicketProviderPort providerPort;
    @Autowired
    private TicketUpdatePort updatePort;

    @Test
    void should_wire真实飞书工单端口_当启用Helpdesk配置() {
        assertNotNull(client);
        assertInstanceOf(FeishuTicketAdapter.class, providerPort);
        assertInstanceOf(FeishuTicketAdapter.class, updatePort);
    }
}
