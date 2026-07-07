package com.smartcs.smart_cs_agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 槽位抽取器测试。
 *
 * 这些测试专门验证“从用户文本里提取字段”的规则，
 * 这样 CustomerServiceSkillTest 就可以更专注于 Action 编排。
 */
class CustomerServiceSlotExtractorTest {

    private final CustomerServiceSlotExtractor extractor = new CustomerServiceSlotExtractor();

    @Test
    void shouldExtractOrderId() {
        assertEquals("ORD-004", extractor.extractOrderId("我想退 ORD-004 这个订单"));
        assertNull(extractor.extractOrderId("我想退这个订单"));
    }

    @Test
    void shouldExtractReturnId() {
        assertEquals("RET-001", extractor.extractReturnId("查一下 RET-001 的状态"));
        assertNull(extractor.extractReturnId("查一下退货状态"));
    }

    @Test
    void shouldExtractReturnReason() {
        assertEquals("质量问题", extractor.extractReturnReason("ORD-004，质量问题", "ORD-004"));
        assertEquals("不想要了", extractor.extractReturnReason("我想退这个订单 ORD-004，不想要了", "ORD-004"));
        assertNull(extractor.extractReturnReason("我要退货 ORD-004", "ORD-004"));
    }

    @Test
    void shouldExtractProductKeyword() {
        assertEquals("耳机", extractor.extractProductKeyword("蓝牙耳机多少钱"));
        assertEquals("", extractor.extractProductKeyword("有什么产品推荐吗"));
    }
}
