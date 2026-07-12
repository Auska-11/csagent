package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SessionStore 版本、ConversationStore 重建恢复和跨实例冲突测试。 */
class SessionStorePersistenceTest {

    @Test
    void shouldIncrementVersionAndRejectStaleWrite() {
        InMemorySessionStore store = new InMemorySessionStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "versioned-conv");
        ConversationState state = new ConversationState("first", "USER-001");

        SessionRecord first = store.save(key, 0, state.toPersistenceSnapshot());
        state.startNewTurn("second");
        SessionRecord second = store.save(key, first.version(), state.toPersistenceSnapshot());

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        SessionVersionConflictException conflict = assertThrows(
                SessionVersionConflictException.class,
                () -> store.save(key, first.version(), state.toPersistenceSnapshot())
        );
        assertEquals(1, conflict.getExpectedVersion());
        assertEquals(2, conflict.getActualVersion());
    }

    @Test
    void shouldRejectSnapshotWhoseOwnerDoesNotMatchKey() {
        InMemorySessionStore store = new InMemorySessionStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "owner-mismatch");
        ConversationState anotherUsersState = new ConversationState("private", "USER-002");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> store.save(key, 0, anotherUsersState.toPersistenceSnapshot())
        );

        assertTrue(error.getMessage().contains("owner does not match"));
    }

    @Test
    void shouldRecoverWaitingWorkflowAfterConversationStoreRecreation() {
        InMemorySessionStore durableBackend = new InMemorySessionStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-002", "restart-conv");

        ConversationStore beforeRestart = new ConversationStore(durableBackend);
        beforeRestart.executeSerially(
                new ChatRequest("restart-conv", "USER-002", "我要退货"),
                state -> {
                    state.startOrResumeWorkflow("return_apply", "2.0.0");
                    state.setWorkflowState("return_apply", "step", "WAITING_CONFIRMATION");
                    state.setWorkflowState("return_apply", "orderId", "ORD-003");
                    state.setWorkflowState("return_apply", "reason", "质量问题");
                    state.markWorkflowWaiting();
                    return null;
                }
        );
        long startedAt = beforeRestart.get(key).getWorkflowStartedAt();

        // 模拟应用重启后重新创建编排层，但底层持久化 SessionStore 仍保留数据。
        ConversationStore afterRestart = new ConversationStore(durableBackend);
        String resumedStep = afterRestart.executeSerially(
                new ChatRequest("restart-conv", "USER-002", "确认提交"),
                state -> {
                    assertEquals(WorkflowStatus.WAITING_USER, state.getWorkflowStatus());
                    assertEquals(startedAt, state.getWorkflowStartedAt());
                    assertEquals("ORD-003", state.getWorkflowState("return_apply", "orderId"));
                    return state.getWorkflowState("return_apply", "step").toString();
                }
        );

        assertEquals("WAITING_CONFIRMATION", resumedStep);
        assertEquals(2, durableBackend.load(key).orElseThrow().version());
        assertEquals(2, afterRestart.get(key).getMessages().size());
    }

    @Test
    void shouldRejectOneOfTwoCrossInstanceWritesUsingSameVersion() throws Exception {
        InMemorySessionStore sharedBackend = new InMemorySessionStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "cas-conv");
        new ConversationStore(sharedBackend).getOrCreate(
                new ChatRequest("cas-conv", "USER-001", "initial")
        );

        ConversationStore firstInstance = new ConversationStore(sharedBackend);
        ConversationStore secondInstance = new ConversationStore(sharedBackend);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> updateCandidate(
                    firstInstance,
                    "first",
                    bothEntered,
                    release
            ));
            Future<String> second = executor.submit(() -> updateCandidate(
                    secondInstance,
                    "second",
                    bothEntered,
                    release
            ));

            assertTrue(bothEntered.await(2, TimeUnit.SECONDS));
            release.countDown();

            int successes = 0;
            int conflicts = 0;
            for (Future<String> future : List.of(first, second)) {
                try {
                    future.get(2, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof SessionVersionConflictException) {
                        conflicts++;
                    } else {
                        throw e;
                    }
                }
            }

            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(2, sharedBackend.load(key).orElseThrow().version());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotPersistHalfFinishedStateWhenOperationFails() {
        InMemorySessionStore backend = new InMemorySessionStore();
        ConversationStore store = new ConversationStore(backend);
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "failure-conv");
        store.getOrCreate(new ChatRequest("failure-conv", "USER-001", "first"));

        assertThrows(IllegalStateException.class, () -> store.executeSerially(
                new ChatRequest("failure-conv", "USER-001", "second"),
                state -> {
                    state.setTaskState("order_id", "SHOULD-NOT-SAVE");
                    throw new IllegalStateException("simulated failure");
                }
        ));

        ConversationState restored = store.get(key);
        assertNull(restored.getTaskState("order_id"));
        assertEquals(1, restored.getMessages().size());
        assertEquals(1, backend.load(key).orElseThrow().version());
    }

    private String updateCandidate(
            ConversationStore store,
            String candidate,
            CountDownLatch bothEntered,
            CountDownLatch release) {
        return store.executeSerially(
                new ChatRequest("cas-conv", "USER-001", candidate),
                state -> {
                    bothEntered.countDown();
                    await(release);
                    state.setTaskState("order_id", candidate);
                    return candidate;
                }
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }
}
