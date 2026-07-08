package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerRoutingTest {

    @Test
    void shouldRouteProductQuestionToPreSalesAgent() {
        SkillManager manager = new SkillManager(new TestSkillConfig());

        AgentRouteResult result = manager.routeAgent("蓝牙耳机多少钱，有货吗");

        assertEquals("pre_sales", result.getRouteKey());
        assertEquals("pre_sales_agent", result.getAgentName());
        assertTrue(result.getConfidence() > 0.8);
    }

    @Test
    void shouldRouteReturnQuestionToAfterSalesAgent() {
        SkillManager manager = new SkillManager(new TestSkillConfig());

        AgentRouteResult result = manager.routeAgent("我要退货，订单 ORD-004");

        assertEquals("after_sales", result.getRouteKey());
        assertEquals("after_sales_agent", result.getAgentName());
    }

    @Test
    void shouldRouteComplaintToComplaintAgentFirst() {
        SkillManager manager = new SkillManager(new TestSkillConfig());

        AgentRouteResult result = manager.routeAgent("我要投诉，还要人工赔偿");

        assertEquals("complaint", result.getRouteKey());
        assertEquals("complaint_agent", result.getAgentName());
    }

    private static class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "test";
        }
    }
}
