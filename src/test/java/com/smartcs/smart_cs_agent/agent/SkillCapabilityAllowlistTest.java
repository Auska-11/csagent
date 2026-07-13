package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCatalog;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.RiskLevel;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证 manifest 能力白名单真的位于执行链路上，而不只是被 Loader 读出来展示。
 */
class SkillCapabilityAllowlistTest {

    @Test
    void shouldDenyCapabilityNotAuthorizedBySelectedSkillPackage() {
        CapabilityCatalog catalog = new CapabilityCatalog(List.of(
                new CapabilityDefinition("order_lookup", "查订单", Map.of(), Map.of()),
                new CapabilityDefinition("product_search", "搜商品", Map.of(), Map.of())
        ));
        AgentSkillPackage skillPackage = packageAllowingOnlyOrderLookup();
        AgentSkillPackageRegistry registry = new AgentSkillPackageRegistry(List.of(skillPackage), catalog);

        SkillManager skillManager = WorkflowTestSupport.skillManager(
                new TestSkillConfig(),
                registry,
                new UnauthorizedCapabilityWorkflow()
        );

        ToolManager toolManager = mock(ToolManager.class);
        AgentRuntime runtime = new AgentRuntime(skillManager, mock(ChatLanguageModel.class), toolManager);
        ConversationState state = new ConversationState("USER-001");
        TurnContext context = new TurnContext(
                new ChatRequest("conv-allowlist", "USER-001", "帮我搜索商品"),
                state
        );

        String answer = runtime.runTurn(context);

        assertEquals("未授权能力已被安全拒绝", answer);
        assertTrue(context.getTraceEvents().stream()
                .anyMatch(event -> "capability_denied".equals(event.getEventType())));
        verifyNoInteractions(toolManager);
    }

    private AgentSkillPackage packageAllowingOnlyOrderLookup() {
        AgentSkillDefinition definition = new AgentSkillDefinition(
                "allowlist-test",
                "1.0.0",
                "能力白名单测试",
                List.of("测试"),
                "allowlist_workflow",
                10,
                Set.of("order_lookup"),
                "SKILL.md",
                List.of(),
                RiskLevel.READ_ONLY,
                ""
        );
        return new AgentSkillPackage(definition, "# 测试提示词", Map.of(), "unit-test");
    }

    /**
     * 第一步故意请求未授权 product_search；第二步看到失败 Observation 后结束。
     */
    private static final class UnauthorizedCapabilityWorkflow implements AgentWorkflow {

        private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
                "allowlist_workflow",
                "test-v1",
                "测试 Skill Package 能力白名单",
                100
        );

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public WorkflowMatchResult match(RoutingContext context) {
            return new WorkflowMatchResult(1.0, "测试固定命中");
        }

        @Override
        public WorkflowDecision nextAction(TurnContext context) {
            if (context.getLatestObservationByType("capability") == null) {
                return WorkflowDecision.next(
                        new CapabilityAction("product_search", Map.of("keyword", "耳机")),
                        "故意请求 manifest 未授权能力"
                );
            }
            return WorkflowDecision.next(
                    new FinalAnswerAction("未授权能力已被安全拒绝"),
                    "已观察到安全拒绝结果"
            );
        }
    }

    private static final class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "legacy prompt should not be used";
        }
    }
}
