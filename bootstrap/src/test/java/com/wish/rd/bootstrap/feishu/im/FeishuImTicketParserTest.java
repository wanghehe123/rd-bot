package com.wish.rd.bootstrap.feishu.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证飞书 IM 文本到内部工单草稿的解析规则。
 */
class FeishuImTicketParserTest {

    private final FeishuImTicketParser parser = new FeishuImTicketParser();

    @Test
    void shouldParseStructuredTicketFields() {
        FeishuImTicketDraft draft = parser.parse("""
                系统: waimai
                仓库: github.com/example/waimai
                分支: main
                优先级: P1
                问题: 下单接口 500
                日志: NullPointerException at OrderService.create
                期望: 下单成功
                实际: 返回 500
                """);

        assertEquals("P1", draft.priority());
        assertEquals("下单接口 500", draft.title());
        assertTrue(draft.description().contains("下单接口 500"));
        assertEquals("waimai", draft.customFields().get("problemSystem"));
        assertEquals("github.com/example/waimai", draft.customFields().get("repository"));
        assertEquals("main", draft.customFields().get("branch"));
        assertEquals("NullPointerException at OrderService.create", draft.customFields().get("logs"));
        assertEquals("下单成功", draft.customFields().get("expectedResult"));
        assertEquals("返回 500", draft.customFields().get("actualResult"));
    }

    @Test
    void shouldParsePipeSeparatedKeyValueFieldsOnOneLine() {
        FeishuImTicketDraft draft = parser.parse(
                "RD-Bot 真实流程测试 2026-06-23 15:56 | 系统: waimai | 优先级: P1 | 问题: 外卖下单接口返回 500 | "
                        + "实际结果: 接口返回 500，下单失败 | 期望结果: 订单创建成功并返回订单号 | "
                        + "错误日志: java.lang.NullPointerException at OrderService.createOrder(OrderService.java:42)"
        );

        assertEquals("P1", draft.priority());
        assertEquals("外卖下单接口返回 500", draft.title());
        assertEquals("waimai", draft.customFields().get("problemSystem"));
        assertEquals("java.lang.NullPointerException at OrderService.createOrder(OrderService.java:42)",
                draft.customFields().get("logs"));
        assertEquals("订单创建成功并返回订单号", draft.customFields().get("expectedResult"));
        assertEquals("接口返回 500，下单失败", draft.customFields().get("actualResult"));
    }

    @Test
    void shouldFallbackToRawTextWhenNoStructuredKeysExist() {
        FeishuImTicketDraft draft = parser.parse("waimai 下单接口 500，日志 NPE");

        assertEquals("P2", draft.priority());
        assertEquals("waimai 下单接口 500，日志 NPE", draft.title());
        assertEquals("waimai 下单接口 500，日志 NPE", draft.description());
        assertTrue(draft.customFields().isEmpty());
    }

    @Test
    void shouldNormalizeUnknownPriorityToP2() {
        FeishuImTicketDraft draft = parser.parse("""
                优先级: 紧急
                问题: 支付失败
                日志: timeout
                """);

        assertEquals("P2", draft.priority());
        assertEquals("支付失败", draft.title());
    }
}
