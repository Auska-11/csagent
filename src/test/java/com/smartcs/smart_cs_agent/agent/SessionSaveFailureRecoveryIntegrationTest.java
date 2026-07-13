package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.capability.local.LocalReturnApplyCapabilityProvider;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionStore;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 模拟“副作用已成功，但会话保存失败”的跨层恢复场景。
 *
 * <p>这类故障比 Provider 直接抛异常更危险：用户可能重试，而第一次操作其实已经生效。测试证明稳定
 * idempotencyKey 会回放第一次结果，重试只负责补上 Session 状态，不会再次提交退货。</p>
 */
@SpringBootTest(properties = "smartcs.test.context=session-save-failure-replay")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionSaveFailureRecoveryIntegrationTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private ReturnRepository returnRepository;

    @SpyBean
    private LocalReturnApplyCapabilityProvider returnApplyProvider;

    @Test
    void shouldReplaySideEffectResultAfterSessionSaveFailure() {
        FailOnceSessionStore sessionStore = new FailOnceSessionStore();
        String conversationId = "session-save-failure-replay";
        ConversationKey key = ConversationKey.defaultTenant("USER-002", conversationId);

        CustomerServiceAgent firstProcess = agentWith(new ConversationStore(sessionStore));
        ChatResponse waiting = firstProcess.chatWithTrace(request(
                conversationId,
                "我要退货 ORD-003，原因是质量问题"
        ));
        assertTrue(waiting.getReply().contains("确认提交"), waiting::getReply);
        assertEquals(1, sessionStore.load(key).orElseThrow().version());

        sessionStore.failNextSave();
        CustomerServiceAgent failingProcess = agentWith(new ConversationStore(sessionStore));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> failingProcess.chatWithTrace(request(conversationId, "确认提交"))
        );

        assertTrue(failure.getMessage().contains("simulated session save failure"));
        // Provider 已执行并创建退货记录，但 Session 仍是失败前的 WAITING_USER v1。
        assertTrue(returnRepository.findByOrderId("ORD-003") != null);
        assertEquals(1, sessionStore.load(key).orElseThrow().version());
        verify(returnApplyProvider, times(1)).invoke(any(), any());

        CustomerServiceAgent restartedProcess = agentWith(new ConversationStore(sessionStore));
        ChatResponse replayed = restartedProcess.chatWithTrace(request(conversationId, "确认提交"));

        assertTrue(replayed.getReply().contains("提交成功"), replayed::getReply);
        assertTrue(replayed.getTrace().stream().anyMatch(event ->
                "tool_observed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("replayed"))
        ));
        // 重试只读取幂等结果，没有第二次进入真实退货 Provider。
        verify(returnApplyProvider, times(1)).invoke(any(), any());
        assertEquals(2, sessionStore.load(key).orElseThrow().version());

        ConversationState restored = new ConversationStore(sessionStore).get(key);
        assertEquals(WorkflowStatus.COMPLETED, restored.getWorkflowStatus());
    }

    private CustomerServiceAgent agentWith(ConversationStore conversationStore) {
        return new CustomerServiceAgent(runtime, conversationStore);
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-002",
                message,
                AuthenticatedPrincipal.authenticated("USER-002", Set.of("CUSTOMER"))
        );
    }

    /** 只在显式开启后拒绝下一次 save，load/delete 仍委托真实内存 SessionStore。 */
    private static final class FailOnceSessionStore implements SessionStore {

        private final InMemorySessionStore delegate = new InMemorySessionStore();
        private final AtomicBoolean failNextSave = new AtomicBoolean();

        @Override
        public Optional<SessionRecord> load(ConversationKey key) {
            return delegate.load(key);
        }

        @Override
        public SessionRecord save(ConversationKey key,
                                  long expectedVersion,
                                  ConversationStateSnapshot snapshot) {
            if (failNextSave.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated session save failure");
            }
            return delegate.save(key, expectedVersion, snapshot);
        }

        @Override
        public boolean delete(ConversationKey key, long expectedVersion) {
            return delegate.delete(key, expectedVersion);
        }

        private void failNextSave() {
            failNextSave.set(true);
        }
    }
}
