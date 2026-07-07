package com.smartcs.smart_cs_agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客服意图规则测试。
 *
 * 这里验证的是高频规则，不涉及 Runtime 和工具调用。
 */
class CustomerServiceIntentRulesTest {

    private final CustomerServiceIntentRules rules = new CustomerServiceIntentRules();

    @Test
    void shouldDetectKnowledgeQuestion() {
        assertTrue(rules.isKnowledgeRelatedMessage("超过七天还能退吗"));
        assertTrue(rules.isKnowledgeRelatedMessage("电子发票什么时候开"));
        assertFalse(rules.isKnowledgeRelatedMessage("我要退货"));
    }

    @Test
    void shouldDetectReturnRelatedMessage() {
        assertTrue(rules.isReturnRelatedMessage("我要退货"));
        assertTrue(rules.isReturnRelatedMessage("质量问题"));
        assertFalse(rules.isReturnRelatedMessage("帮我查订单"));
    }

    @Test
    void shouldClassifyHandoffReason() {
        assertEquals("COMPLAINT", rules.classifyHandoffReason("我要投诉你们"));
        assertEquals("COMPENSATION_OR_DISPUTE", rules.classifyHandoffReason("我要赔偿并且维权"));
        assertEquals("USER_REQUESTED_HUMAN", rules.classifyHandoffReason("帮我转人工"));
    }
}
