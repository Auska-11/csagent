package com.smartcs.smart_cs_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.escalation.EscalationWorkflow;
import com.smartcs.smart_cs_agent.workflow.general.GeneralCustomerServiceWorkflow;
import com.smartcs.smart_cs_agent.workflow.orderquery.OrderQueryWorkflow;
import com.smartcs.smart_cs_agent.workflow.policyqa.PolicyQaWorkflow;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentRuntime Trace 单元测试。
 *
 * 这个测试不调用真实 LLM，而是使用 FakeChatLanguageModel 返回固定文本。
 * 这样测试只验证我们自己的 Runtime、Tool、Trace 链路，速度更快，也不会依赖网络。
 */
class AgentRuntimeTraceTest {

    @Test
    void shouldRecordTraceForCapabilityFlowWithoutLegacyLlmHop() {
        AgentRuntime runtime = createRuntime("专业 Workflow 不应调用这个 Fake LLM");

        ChatRequest request = request("conv-trace", "帮我查一下订单 ORD-001");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertTrue(reply.contains("ORD-001"));
        assertTrue(reply.contains("蓝牙耳机 Pro Max"));
        assertTrue(context.isDone());
        assertEquals(reply, context.getFinalAnswer());

        List<String> eventTypes = context.getTraceEvents()
                .stream()
                .map(TraceEvent::getEventType)
                .toList();

        assertTrue(eventTypes.contains("turn_started"));
        assertTrue(eventTypes.contains("memory_loaded"));
        assertTrue(eventTypes.contains("skill_selected"));
        assertTrue(eventTypes.contains("action_planned"));
        assertTrue(eventTypes.contains("tool_called"));
        assertTrue(eventTypes.contains("tool_observed"));
        assertFalse(eventTypes.contains("llm_called"));
        assertFalse(eventTypes.contains("llm_observed"));
        assertTrue(eventTypes.contains("final_answer"));

        assertHasDuration(findTraceEvent(context, "tool_observed"));
    }

    @Test
    void shouldRecordTraceForHandoffFlow() {
        AgentRuntime runtime = createRuntime("这个测试不会调用 LLM");

        ChatRequest request = request("conv-handoff", "我要转人工投诉");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertTrue(reply.contains("工单号"));
        assertTrue(context.isDone());

        List<String> eventTypes = context.getTraceEvents()
                .stream()
                .map(TraceEvent::getEventType)
                .toList();

        assertTrue(eventTypes.contains("handoff_requested"));
        assertTrue(eventTypes.contains("handoff_created"));
        assertTrue(eventTypes.contains("final_answer"));

        assertHasDuration(findTraceEvent(context, "handoff_created"));
    }

    @Test
    void shouldFallbackWhenLlmFailsForGeneralWorkflow() {
        AgentRuntime runtime = createRuntime(new FailingChatLanguageModel());

        ChatRequest request = request("conv-llm-fallback", "你好");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertTrue(reply.contains("大模型服务暂时不可用"));
        assertTrue(context.isDone());

        List<String> eventTypes = context.getTraceEvents()
                .stream()
                .map(TraceEvent::getEventType)
                .toList();

        assertFalse(eventTypes.contains("tool_observed"));
        assertTrue(eventTypes.contains("llm_failed"));
        assertTrue(eventTypes.contains("llm_observed"));
        assertTrue(eventTypes.contains("final_answer"));

        TraceEvent failed = findTraceEvent(context, "llm_failed");
        assertEquals("LLM_PROVIDER_ERROR", failed.getData().get("errorCode"));
        assertFalse(failed.getData().containsKey("error"));
    }

    @Test
    void shouldRecordKnowledgeMissWhenKnowledgeSearchHasNoResult() {
        AgentRuntime runtime = createRuntime("知识库没有找到明确政策，请转人工确认。");

        ChatRequest request = request("conv-knowledge-miss", "纸质发票可以补开吗");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        runtime.runTurn(context);

        List<String> eventTypes = context.getTraceEvents()
                .stream()
                .map(TraceEvent::getEventType)
                .toList();

        assertTrue(eventTypes.contains("tool_observed"));
        assertTrue(eventTypes.contains("knowledge_miss"));
        assertTrue(eventTypes.contains("final_answer"));
    }

    @Test
    void shouldKeepRawLlmDataForExecutionButOnlyExposeSanitizedTrace() throws Exception {
        String rawMessage = "你好，TRACE-USER-CANARY，手机号 13800138000，token=runtime-secret";
        String rawReply = "TRACE-LLM-REPLY，请联系 private@example.com";
        CapturingChatLanguageModel chatModel = new CapturingChatLanguageModel(rawReply);
        AgentRuntime runtime = createRuntime(chatModel);

        ChatRequest request = request("conv-trace-privacy", rawMessage);
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        // 脱敏只作用于可观测副本，不能改变真正交给模型的上下文或最终业务回复。
        assertEquals(rawReply, reply);
        assertTrue(chatModel.getLastMessages().stream()
                .map(ChatMessage::text)
                .anyMatch(text -> text.contains(rawMessage)));

        String traceJson = new ObjectMapper().writeValueAsString(context.getTraceEvents());
        assertFalse(traceJson.contains("TRACE-USER-CANARY"));
        assertFalse(traceJson.contains("13800138000"));
        assertFalse(traceJson.contains("runtime-secret"));
        assertFalse(traceJson.contains("TRACE-LLM-REPLY"));
        assertFalse(traceJson.contains("private@example.com"));
    }

    private AgentRuntime createRuntime(String fakeLlmReply) {
        return createRuntime(new FakeChatLanguageModel(fakeLlmReply));
    }

    private AgentRuntime createRuntime(ChatLanguageModel chatModel) {
        GlobalInterruptPolicy interruptPolicy = new GlobalInterruptPolicy();
        SkillManager skillManager = WorkflowTestSupport.skillManager(
                new TestSkillConfig(),
                new GeneralCustomerServiceWorkflow(),
                new OrderQueryWorkflow(interruptPolicy),
                new PolicyQaWorkflow(interruptPolicy),
                new EscalationWorkflow(interruptPolicy)
        );

        ProductRepository productRepository = new ProductRepository();
        OrderRepository orderRepository = new OrderRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        UserRepository userRepository = new UserRepository();

        return new AgentRuntime(
                skillManager,
                WorkflowTestSupport.actionHandlerRegistry(
                        skillManager,
                        chatModel,
                        WorkflowTestSupport.localCapabilityGateway(
                                orderRepository,
                                productRepository,
                                returnRepository,
                                userRepository,
                                createKnowledgeTools(),
                                new HandoffTools()
                        )
                )
        );
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-001",
                message,
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        );
    }

    private KnowledgeTools createKnowledgeTools() {
        KnowledgeDocument document = new KnowledgeDocument(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                "超过 7 天后，不再默认支持七天无理由退货。",
                List.of("退货", "七天")
        );
        return new KnowledgeTools(List.of(document));
    }

    private TraceEvent findTraceEvent(TurnContext context, String eventType) {
        return context.getTraceEvents()
                .stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .findFirst()
                .orElseThrow();
    }

    private void assertHasDuration(TraceEvent event) {
        Object durationMs = event.getData().get("durationMs");
        assertTrue(durationMs instanceof Number);
        assertTrue(((Number) durationMs).longValue() >= 0);
    }

    /**
     * 测试用 SkillConfig。
     *
     * 真实 SkillConfig 会从 resources 读取 system-prompt.md。
     * 单元测试里直接返回固定字符串即可，避免启动完整 Spring 环境。
     */
    private static class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "你是测试环境中的客服助手。";
        }
    }

    /**
     * 测试用假模型。
     *
     * Runtime 只需要 ChatLanguageModel.generate(...) 返回一个 AiMessage。
     * 这里固定返回 reply，避免真实调用大模型。
     */
    private static class FakeChatLanguageModel implements ChatLanguageModel {
        private final String reply;

        private FakeChatLanguageModel(String reply) {
            this.reply = reply;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from(reply));
        }
    }

    /** 记录模型实际收到的消息，用来证明 Trace 脱敏没有污染业务输入。 */
    private static class CapturingChatLanguageModel implements ChatLanguageModel {
        private final String reply;
        private List<ChatMessage> lastMessages = List.of();

        private CapturingChatLanguageModel(String reply) {
            this.reply = reply;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            this.lastMessages = List.copyOf(messages);
            return Response.from(AiMessage.from(reply));
        }

        private List<ChatMessage> getLastMessages() {
            return lastMessages;
        }
    }

    /**
     * 测试用失败模型。
     *
     * 用它模拟真实开发里常见的情况：
     * API key 失效、网络不可用、模型服务超时。
     */
    private static class FailingChatLanguageModel implements ChatLanguageModel {
        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            throw new RuntimeException("mock llm failure");
        }
    }
}
