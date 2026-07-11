package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构改造前的客服主链路特征测试（Characterization Test）。
 *
 * <p>“特征测试”不是为了证明当前实现已经完美，而是先把目前对外可见的正确行为固定下来。
 * 后续我们会把 {@code CustomerServiceSkill} 拆成多个 Workflow；只要这个测试仍然通过，就说明
 * 拆分类和调整依赖没有破坏用户已经能使用的三轮退货流程。</p>
 *
 * <p>调用链：CustomerServiceAgent -> ConversationStore -> AgentRuntime ->
 * CustomerServiceSkill -> ToolManager -> ToolRegistry -> ReturnTools。</p>
 *
 * <p>测试使用 Fake LLM 和内存 Repository，不访问网络或真实数据库，因此可以稳定地加入默认
 * {@code mvn test}。</p>
 */
class CustomerServiceAgentCharacterizationTest {

    @Test
    void shouldKeepReturnApplicationStateAcrossThreeAgentTurns() {
        TestFixture fixture = createFixture();
        String conversationId = "characterization-return-flow";
        String userId = "USER-002";

        // 第一轮只有业务意图，没有订单号。Agent 应记录 pending_slot=order_id 并向用户追问。
        ChatResponse first = fixture.agent().chatWithTrace(
                authenticatedRequest(conversationId, userId, "我要退货")
        );
        assertTrue(first.getReply().contains("订单号"));
        assertTrue(hasTrace(first, "ask_user"));
        assertFalse(hasTrace(first, "tool_called"));

        ConversationState stateAfterFirstTurn = fixture.conversationStore().get(conversationId);
        assertNotNull(stateAfterFirstTurn);
        assertEquals("return_apply", stateAfterFirstTurn.getActiveIntent());
        assertEquals("order_id", stateAfterFirstTurn.getPendingSlot());

        // 第二轮用户只回复订单号。系统必须依靠同一会话中的任务状态理解这是在补退货槽位。
        ChatResponse second = fixture.agent().chatWithTrace(
                authenticatedRequest(conversationId, userId, "ORD-003")
        );
        assertTrue(second.getReply().contains("退货原因"));
        assertTrue(hasTrace(second, "ask_user"));
        assertFalse(hasTrace(second, "tool_called"));

        ConversationState stateAfterSecondTurn = fixture.conversationStore().get(conversationId);
        assertEquals("ORD-003", stateAfterSecondTurn.getTaskState("order_id"));
        assertEquals("return_reason", stateAfterSecondTurn.getPendingSlot());

        // 第三轮再次只有槽位值。旧架构应调用 return_apply，然后通过 Fake LLM 生成最终回复。
        ChatResponse third = fixture.agent().chatWithTrace(
                authenticatedRequest(conversationId, userId, "质量问题")
        );
        assertEquals("退货申请已提交，请留意后续审核进度。", third.getReply());
        assertTrue(hasTrace(third, "tool_called"));
        assertTrue(hasTrace(third, "tool_observed"));
        assertTrue(hasTrace(third, "llm_called"));
        assertTrue(hasTrace(third, "final_answer"));

        // 不只检查回复，还检查副作用确实发生，防止未来出现“嘴上说提交、实际没写入”的假闭环。
        ReturnRecord savedRecord = fixture.returnRepository().findByOrderId("ORD-003");
        assertNotNull(savedRecord);
        assertEquals("质量问题", savedRecord.getReason());

        // 任务完成后，订单号、原因和 pending_slot 应被清理，避免污染用户的下一项任务。
        ConversationState completedState = fixture.conversationStore().get(conversationId);
        assertNull(completedState.getPendingSlot());
        assertNull(completedState.getTaskState("order_id"));
        assertNull(completedState.getTaskState("return_reason"));
    }

    private boolean hasTrace(ChatResponse response, String eventType) {
        return response.getTrace().stream()
                .anyMatch(event -> eventType.equals(event.getEventType()));
    }

    private ChatRequest authenticatedRequest(String conversationId, String userId, String message) {
        return new ChatRequest(
                conversationId,
                userId,
                message,
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
        );
    }

    /**
     * 手动组装一套完全在内存中运行的 Agent。
     *
     * <p>这里故意不启动 Spring：特征测试关心的是 Java 主链路和状态变化，而不是容器装配。
     * 以后替换 Workflow 或 Capability 实现时，这个 Fixture 也能帮助我们快速定位是哪一层发生回归。</p>
     */
    private TestFixture createFixture() {
        ProductRepository productRepository = new ProductRepository();
        OrderRepository orderRepository = new OrderRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        UserRepository userRepository = new UserRepository();

        ToolRegistry toolRegistry = new ToolRegistry(
                new OrderTools(orderRepository, productRepository),
                new ProductTools(productRepository),
                new ReturnTools(returnRepository, orderRepository, productRepository),
                new UserTools(userRepository),
                new KnowledgeTools(),
                new HandoffTools()
        );

        SkillManager skillManager = new SkillManager(new TestSkillConfig());
        AgentRuntime runtime = new AgentRuntime(
                skillManager,
                new FixedReplyChatLanguageModel("退货申请已提交，请留意后续审核进度。"),
                new ToolManager(toolRegistry)
        );
        ConversationStore conversationStore = new ConversationStore();
        CustomerServiceAgent agent = new CustomerServiceAgent(
                runtime,
                skillManager,
                new CustomerServiceSkill(null),
                conversationStore
        );
        return new TestFixture(agent, conversationStore, returnRepository);
    }

    private record TestFixture(CustomerServiceAgent agent,
                               ConversationStore conversationStore,
                               ReturnRepository returnRepository) {
    }

    private static class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "你是测试环境中的智能客服。";
        }
    }

    /**
     * 固定回复模型只负责隔离外部 LLM 的不确定性。
     * 工具是否被调用、状态是否正确推进，仍由真实 Agent 主链路完成。
     */
    private static class FixedReplyChatLanguageModel implements ChatLanguageModel {
        private final String reply;

        private FixedReplyChatLanguageModel(String reply) {
            this.reply = reply;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from(reply));
        }
    }
}
