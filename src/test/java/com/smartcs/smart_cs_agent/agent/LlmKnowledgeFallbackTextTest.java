package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionFlow;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.defaults.LlmActionHandler;
import com.smartcs.smart_cs_agent.agent.llm.LlmExecutionGuard;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 故障降级话术的买家可读性（压测 #3 病症）：knowledge.search 的 message 是给模型的
 * 证据文本（Prompt 指令“知识库可靠证据：只能根据以下内容回答…”+ 相关度/召回通道/命中词
 * 等检索调试字段），老降级路径把它原样发给了真实买家。降级文案必须只用命中标题+片段组织。
 */
class LlmKnowledgeFallbackTextTest {

    @Test
    void knowledgeSearchFallbackMustUseTitleAndSnippetOnly() {
        LlmActionHandler handler = handler();
        TurnContext turn = turn();
        turn.getState().setTaskState("last_capability_result", knowledgeObservation(List.of(
                new KnowledgeSearchHit(
                        "product/1079979582985.md",
                        "product/1079979582985.md#chunk-2",
                        "POLICY",
                        "product",
                        "xianyu",
                        "",
                        "森海塞尔MOMENTUM二代头戴耳机 / 商品描述",
                        "xianyu-item-snapshot",
                        "1",
                        84,
                        "支持蓝牙和有线连接，米白+黑色配色。"
                )
        )));

        ActionExecutionResult result = handler.handle(
                new LLMAction("response_generation", "请根据证据回答", Map.of()),
                handlingContext(turn)
        );

        assertEquals(ActionFlow.CONTINUE, result.getFlow());
        String reply = String.valueOf(turn.getLatestObservationByType("llm_response").getData());
        assertTrue(reply.contains("【森海塞尔MOMENTUM二代头戴耳机 / 商品描述】"), reply);
        assertTrue(reply.contains("支持蓝牙和有线连接"), "降级回复应保留知识片段正文");
        assertFalse(reply.contains("知识库可靠证据"), "不得把 Prompt 指令骨架发给买家");
        assertFalse(reply.contains("只能根据以下内容回答"), "不得把 Prompt 指令骨架发给买家");
        assertFalse(reply.contains("相关度"), "不得把检索调试元数据发给买家");
        assertFalse(reply.contains("召回通道"), "不得把检索调试元数据发给买家");
        assertFalse(reply.contains("命中词"), "不得把检索调试元数据发给买家");
        assertFalse(reply.contains("向量分"), "不得把检索调试元数据发给买家");
        assertTrue(turn.getTraceEvents().stream().anyMatch(event -> "llm_failed".equals(event.getEventType())));
    }

    @Test
    void emptyKnowledgeHitsFallbackKeepsHonestApology() {
        LlmActionHandler handler = handler();
        TurnContext turn = turn();
        turn.getState().setTaskState("last_capability_result", knowledgeObservation(List.of()));

        ActionExecutionResult result = handler.handle(
                new LLMAction("response_generation", "请根据证据回答", Map.of()),
                handlingContext(turn)
        );

        assertEquals(ActionFlow.CONTINUE, result.getFlow());
        String reply = String.valueOf(turn.getLatestObservationByType("llm_response").getData());
        assertTrue(reply.contains("暂时无法给出准确答复"), reply);
        assertTrue(reply.contains("我要找人工客服"), "降级回复必须给出人工出口");
        assertFalse(reply.contains("知识库可靠证据"));
    }

    @Test
    void nonKnowledgeCapabilitiesKeepTheirFallbackPrefixes() {
        LlmActionHandler handler = handler();
        TurnContext turn = turn();
        turn.getState().setTaskState("last_capability_result", new CapabilityObservation(
                CapabilityIds.ORDER_LOOKUP,
                CapabilityOutcome.SUCCESS,
                "订单 ORD-001 已发货，物流单号 SF123456。",
                Map.of(),
                null,
                "local-order",
                false
        ));

        handler.handle(
                new LLMAction("response_generation", "请回答", Map.of()),
                handlingContext(turn)
        );

        String reply = String.valueOf(turn.getLatestObservationByType("llm_response").getData());
        assertTrue(reply.startsWith("我已为您查询到订单信息"), reply);
        assertTrue(reply.contains("SF123456"));
    }

    private LlmActionHandler handler() {
        SkillManager skillManager = new SkillManager(new TestSkillConfig());
        LlmExecutionGuard executionGuard = mock(LlmExecutionGuard.class);
        when(executionGuard.generate(any(), any(), any())).thenReturn(
                new LlmExecutionGuard.LlmInvocationResult(
                        false,
                        null,
                        "LLM_CALL_DEADLINE_EXCEEDED",
                        30,
                        1_000
                )
        );
        return new LlmActionHandler(skillManager, mock(ChatLanguageModel.class), executionGuard);
    }

    private CapabilityObservation knowledgeObservation(List<KnowledgeSearchHit> hits) {
        return new CapabilityObservation(
                CapabilityIds.KNOWLEDGE_SEARCH,
                CapabilityOutcome.SUCCESS,
                "知识库可靠证据：只能根据以下内容回答，不要编造或补充知识库未出现的事实。\n"
                        + "1. 标题：…\n相关度：84\n召回通道：keyword\n命中词：蓝牙\n向量分：0.00\n片段：…",
                hits,
                null,
                "local-knowledge-search",
                false
        );
    }

    private TurnContext turn() {
        ChatRequest request = new ChatRequest("conv-knowledge-fallback", "USER-XY-test", "连不上蓝牙");
        return new TurnContext(request, new ConversationState(request.getMessage(), request.getUserId()));
    }

    private ActionHandlingContext handlingContext(TurnContext turn) {
        return new ActionHandlingContext(turn, mock(AgentWorkflow.class), null);
    }

    private static final class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "test system prompt";
        }
    }
}
