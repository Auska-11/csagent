package com.smartcs.smart_cs_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import com.smartcs.smart_cs_agent.tools.OrderTools;
import com.smartcs.smart_cs_agent.tools.ProductTools;
import com.smartcs.smart_cs_agent.tools.ReturnTools;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolRegistry;
import com.smartcs.smart_cs_agent.tools.UserTools;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void shouldRecordTraceForToolAndLlmFlow() {
        AgentRuntime runtime = createRuntime("这是根据工具结果生成的测试回复");

        ChatRequest request = new ChatRequest("conv-trace", "USER-001", "帮我查一下订单 ORD-001");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertEquals("这是根据工具结果生成的测试回复", reply);
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
        assertTrue(eventTypes.contains("llm_called"));
        assertTrue(eventTypes.contains("llm_observed"));
        assertTrue(eventTypes.contains("final_answer"));

        assertHasDuration(findTraceEvent(context, "tool_observed"));
        assertHasDuration(findTraceEvent(context, "llm_observed"));
    }

    @Test
    void shouldRecordTraceForHandoffFlow() {
        AgentRuntime runtime = createRuntime("这个测试不会调用 LLM");

        ChatRequest request = new ChatRequest("conv-handoff", "USER-001", "我要转人工投诉");
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
    void shouldFallbackWhenLlmFailsAfterToolCall() {
        AgentRuntime runtime = createRuntime(new FailingChatLanguageModel());

        ChatRequest request = new ChatRequest("conv-llm-fallback", "USER-001", "帮我查一下订单 ORD-001");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertTrue(reply.contains("我已为您查询到订单信息"));
        assertTrue(context.isDone());

        List<String> eventTypes = context.getTraceEvents()
                .stream()
                .map(TraceEvent::getEventType)
                .toList();

        assertTrue(eventTypes.contains("tool_observed"));
        assertTrue(eventTypes.contains("llm_failed"));
        assertTrue(eventTypes.contains("llm_observed"));
        assertTrue(eventTypes.contains("final_answer"));
    }

    @Test
    void shouldRecordKnowledgeMissWhenKnowledgeSearchHasNoResult() {
        AgentRuntime runtime = createRuntime("知识库没有找到明确政策，请转人工确认。");

        ChatRequest request = new ChatRequest("conv-knowledge-miss", "USER-001", "纸质发票可以补开吗");
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

        ChatRequest request = new ChatRequest("conv-trace-privacy", "USER-001", rawMessage);
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
        SkillManager skillManager = new SkillManager(new TestSkillConfig());
        skillManager.registerSkill(new CustomerServiceSkill(null));

        ProductRepository productRepository = new ProductRepository();
        OrderRepository orderRepository = new OrderRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        UserRepository userRepository = new UserRepository();

        ToolRegistry toolRegistry = new ToolRegistry(
                new OrderTools(orderRepository, productRepository),
                new ProductTools(productRepository),
                new ReturnTools(returnRepository, orderRepository, productRepository),
                new UserTools(userRepository),
                createKnowledgeTools(),
                new HandoffTools()
        );
        ToolManager toolManager = new ToolManager(toolRegistry);

        return new AgentRuntime(
                skillManager,
                chatModel,
                toolManager
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
