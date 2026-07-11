package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.skill.Skill;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.legacy.LegacySkillWorkflowAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 验证旧 Skill 到新 Workflow 协议的迁移桥梁。
 *
 * <p>这个测试的意义不是长期保留 Legacy Adapter，而是保证迁移期间 Runtime 换成 Workflow 类型后，
 * 旧 Skill 的匹配分数和下一步 Action 没有被 Adapter 改写。</p>
 */
class LegacySkillWorkflowAdapterTest {

    @Test
    void shouldExposeLegacySkillThroughWorkflowContract() {
        LegacySkillWorkflowAdapter workflow = new LegacySkillWorkflowAdapter(new TestSkill());
        ChatRequest request = new ChatRequest("conv-workflow", "USER-001", "我要查询订单");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());

        WorkflowMatchResult match = workflow.match(new RoutingContext(request, state));
        WorkflowDecision decision = workflow.nextAction(new TurnContext(request, state));

        assertEquals("test_skill", workflow.descriptor().getId());
        assertEquals("legacy-v1", workflow.descriptor().getVersion());
        assertEquals(0.72, match.getScore(), 0.0001);

        AskUserAction askUser = assertInstanceOf(AskUserAction.class, decision.getAction());
        assertEquals("请提供订单号。", askUser.getQuestion());
        assertEquals("由旧 Skill.nextAction 生成，等待迁移为独立 Workflow", decision.getDecisionReason());
    }

    private static class TestSkill implements Skill {
        @Override
        public AgentAction nextAction(TurnContext context) {
            return new AskUserAction("请提供订单号。");
        }

        @Override
        public String getName() {
            return "test_skill";
        }

        @Override
        public float canHandle(String message) {
            return 0.72f;
        }
    }
}
