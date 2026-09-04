package com.smartcs.smart_cs_agent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站安全过滤的纯单元测试。
 *
 * <p>重点守护两类正确性：联系方式交换意图必须被整条替换（安全约束）；
 * 知识库排查话术中合法出现的平台名不能被误杀（上游裸关键词方案的教训，
 * 见 watch_s2.md 的“微信不提醒”）。</p>
 */
class OutboundSafetyFilterTest {

    @Test
    void shouldReplaceContactExchangeIntentReplies() {
        OutboundSafetyFilter filter = OutboundSafetyFilter.withDefaults();

        assertReplaced(filter, "您可以加我微信 abc123 私聊，秒回");
        assertReplaced(filter, "我微信号是 shop888，加一下");
        assertReplaced(filter, "加个vx吧，方便联系");
        assertReplaced(filter, "加QQ：123456 详聊");
        assertReplaced(filter, "扣扣 998877 快加");
        assertReplaced(filter, "支付宝转账给我就行，便宜点");
        assertReplaced(filter, "把银行卡号发我，直接给你打款");
        assertReplaced(filter, "咱们线下面交吧，省运费");
        assertReplaced(filter, "私下交易不走平台，双向安全");
    }

    @Test
    void shouldKeepLegitTroubleshootingReplyMentioningWechat() {
        OutboundSafetyFilter filter = OutboundSafetyFilter.withDefaults();

        // 直接取自 watch_s2.md 排查语义：平台名单词出现，但没有“交换联系方式”的意图。
        OutboundSafetyFilter.Outcome watch = filter.apply(
                "手表微信不提醒时，请先在手机上确认微信通知权限，再检查后台运行设置。"
        );
        assertFalse(watch.filtered());
        assertTrue(watch.text().contains("微信不提醒"));

        assertKept(filter, "耳机支持微信语音通话使用，如仍有杂音请尝试重置配对。");
        assertKept(filter, "退货政策为七天无理由，请保留好商品吊牌。");
    }

    @Test
    void shouldPassThroughCleanAndBlankText() {
        OutboundSafetyFilter filter = OutboundSafetyFilter.withDefaults();

        assertKept(filter, "已为您查询到订单 ORD-002，商品为蓝牙耳机 Pro Max。");
        assertFalse(filter.apply("").filtered());
        assertFalse(filter.apply("   ").filtered());
        assertEquals("", filter.apply("").text());
    }

    @Test
    void shouldExposeMatchedPatternsWithoutOriginalText() {
        OutboundSafetyFilter filter = OutboundSafetyFilter.withDefaults();

        OutboundSafetyFilter.Outcome outcome = filter.apply("加我微信 shop888 私聊");

        assertTrue(outcome.filtered());
        assertEquals(OutboundSafetyFilter.DEFAULT_REPLACEMENT, outcome.text());
        // 模式原文可用于定位问题，但不能把命中的联系方式片段带出去。
        assertEquals(List.of("加.{0,4}微信"), outcome.matchedPatterns());
        assertFalse(outcome.text().contains("shop888"));
    }

    @Test
    void shouldHonorDisabledSwitchAndCustomReplacement() {
        OutboundSafetyFilter filter = OutboundSafetyFilter.withDefaults();
        filter.setEnabled(false);
        assertFalse(filter.apply("您可以加我微信 abc123").filtered());

        OutboundSafetyFilter custom = OutboundSafetyFilter.withDefaults();
        custom.setReplacement("请留在平台内沟通。");
        OutboundSafetyFilter.Outcome outcome = custom.apply("加我微信 abc123");
        assertTrue(outcome.filtered());
        assertEquals("请留在平台内沟通。", outcome.text());

        // 空白替换话术回退默认，避免配置失误导致回复变成空串。
        custom.setReplacement(" ");
        assertEquals(OutboundSafetyFilter.DEFAULT_REPLACEMENT, custom.apply("加我微信 abc123").text());
    }

    @Test
    void shouldUseOverriddenPatternListOnly() {
        OutboundSafetyFilter filter = OutboundSafetyFilter.withDefaults();
        filter.setBlockedPatterns(List.of("转.{0,4}支付宝"));

        // 默认清单被整体覆盖：未包含的微信话术不再命中。
        assertFalse(filter.apply("加我微信 abc123").filtered());
        assertTrue(filter.apply("转我支付宝吧").filtered());
    }

    private void assertReplaced(OutboundSafetyFilter filter, String reply) {
        OutboundSafetyFilter.Outcome outcome = filter.apply(reply);
        assertTrue(outcome.filtered(), "应被过滤: " + reply);
        assertEquals(OutboundSafetyFilter.DEFAULT_REPLACEMENT, outcome.text());
    }

    private void assertKept(OutboundSafetyFilter filter, String reply) {
        OutboundSafetyFilter.Outcome outcome = filter.apply(reply);
        assertFalse(outcome.filtered(), "不应被过滤: " + reply);
        assertEquals(reply, outcome.text());
    }
}
