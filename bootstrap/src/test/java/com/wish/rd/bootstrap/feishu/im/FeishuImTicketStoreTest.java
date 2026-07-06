package com.wish.rd.bootstrap.feishu.im;

import com.wish.rd.adapter.model.TicketMessageQuery;
import com.wish.rd.adapter.model.TicketMessages;
import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.bootstrap.feishu.im.model.FeishuImTicketDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证飞书 IM 本地工单存储的读写与快照不可变性。
 */
class FeishuImTicketStoreTest {

    @Test
    void shouldRegisterTicketAndMessageFromImDraft() {
        FeishuImTicketParser parser = new FeishuImTicketParser();
        FeishuImTicketDraft draft = parser.parse("""
                系统: waimai
                仓库: github.com/example/waimai
                分支: main
                优先级: P1
                问题: 下单接口 500
                日志: NullPointerException
                """);
        FeishuImTicketStore store = new FeishuImTicketStore();

        TicketSnapshot snapshot = store.registerFromMessage(
                "FI-om-1",
                "oc-chat",
                "ou-user",
                "om-1",
                "系统: waimai\n问题: 下单接口 500",
                draft,
                Instant.parse("2026-06-23T10:15:30Z")
        );

        assertEquals("FI-om-1", snapshot.ticketId());
        assertEquals("feishu-im", snapshot.source());
        assertEquals("oc-chat", snapshot.chatId());
        assertEquals("P1", snapshot.priority());
        assertEquals("waimai", snapshot.customFields().get("problemSystem"));
        assertTrue(store.findTicket("FI-om-1").isPresent());

        TicketMessages messages = store.findMessages("FI-om-1", TicketMessageQuery.defaults());
        assertEquals(1, messages.messages().size());
        assertEquals("om-1", messages.messages().get(0).messageId());
        assertEquals("2", messages.messages().get(0).senderType());
        assertEquals("ou-user", messages.messages().get(0).senderId());
    }

    @Test
    void shouldReturnImmutableMessageSnapshots() {
        FeishuImTicketStore store = new FeishuImTicketStore();
        FeishuImTicketDraft draft = new FeishuImTicketParser().parse("waimai 下单接口 500");
        store.registerFromMessage(
                "FI-om-2",
                "oc-chat",
                "ou-user",
                "om-2",
                "waimai 下单接口 500",
                draft,
                Instant.parse("2026-06-23T10:15:30Z")
        );

        TicketMessages messages = store.findMessages("FI-om-2", TicketMessageQuery.defaults());

        assertThrows(UnsupportedOperationException.class, () -> messages.messages().clear());
    }
}
