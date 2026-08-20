package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.feishu.im.FeishuImMessageController;
import com.wish.rd.bootstrap.threading.RequirementDeliveryDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证飞书 IM 需求入口只靠 {@code rd.feishu.im.enabled} 就能装配，不再依赖已删除的工单开关。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rd.knowledge.store=memory",
                "rd.feishu.im.enabled=true",
                "rd.feishu.im.app-id=app-id-for-wiring-test",
                "rd.feishu.im.app-secret=app-secret-for-wiring-test"
        }
)
class FeishuImBeanWiringTest {

    @Autowired
    private FeishuImMessageController controller;
    @Autowired
    private RequirementDeliveryDispatchService requirementDeliveryDispatchService;

    @Test
    void shouldWireFeishuImRequirementIntakeWithoutTheTicketProviderSwitch() {
        assertNotNull(controller);
        assertNotNull(requirementDeliveryDispatchService);
    }
}
