package com.smartcs.smart_cs_agent.channel.xianyu;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * parseConversationId 是 SLA v1 建单坐标的唯一来源：非闲鱼或畸形会话 ID 必须 fail-closed，
 * 不能猜测 buyerId/itemId——猜错会把人工结果记到别的买家/别的商品名下。
 */
class XianyuIdentityParseTest {

    @Test
    void shouldParseBuyerAndItemFromValidConversationId() {
        Optional<XianyuIdentity.XianyuCoordinates> parsed =
                XianyuIdentity.parseConversationId("xianyu_12345_67890");

        assertTrue(parsed.isPresent());
        assertEquals("12345", parsed.orElseThrow().buyerId());
        assertEquals("67890", parsed.orElseThrow().itemId());
    }

    @Test
    void shouldRoundTripWithConversationIdBuilder() {
        String conversationId = XianyuIdentity.conversationId("111", "222");

        Optional<XianyuIdentity.XianyuCoordinates> parsed =
                XianyuIdentity.parseConversationId(conversationId);

        assertEquals("111", parsed.orElseThrow().buyerId());
        assertEquals("222", parsed.orElseThrow().itemId());
    }

    @Test
    void shouldRejectMalformedConversationIds() {
        assertFalse(XianyuIdentity.parseConversationId(null).isPresent());
        assertFalse(XianyuIdentity.parseConversationId("").isPresent());
        // 不是闲鱼会话：绝不能把前缀之外的部分当成坐标。
        assertFalse(XianyuIdentity.parseConversationId("webchat_user-1_item-1").isPresent());
        // 缺任一段 ID。
        assertFalse(XianyuIdentity.parseConversationId("xianyu_12345_").isPresent());
        assertFalse(XianyuIdentity.parseConversationId("xianyu__67890").isPresent());
        assertFalse(XianyuIdentity.parseConversationId("xianyu_").isPresent());
        // 闲鱼平台 ID 为纯数字；字母 ID 视为非闲鱼来源。
        assertFalse(XianyuIdentity.parseConversationId("xianyu_buyer-a_67890").isPresent());
        assertFalse(XianyuIdentity.parseConversationId("xianyu_12345_item-b").isPresent());
        // 多余的下划线说明坐标结构不确定（第三段无法归属），同样拒绝。
        assertFalse(XianyuIdentity.parseConversationId("xianyu_12345_67890_extra").isPresent());
    }
}
