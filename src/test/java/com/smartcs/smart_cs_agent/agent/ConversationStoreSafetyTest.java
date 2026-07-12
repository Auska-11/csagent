package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话归属与同会话串行化安全测试。 */
class ConversationStoreSafetyTest {

    @Test
    void shouldIsolateSameConversationIdAcrossUsers() {
        ConversationStore store = new ConversationStore();
        ConversationKey firstKey = ConversationKey.defaultTenant("USER-001", "shared-conv");
        ConversationKey secondKey = ConversationKey.defaultTenant("USER-002", "shared-conv");

        ConversationState first = store.getOrCreate(
                new ChatRequest("shared-conv", "USER-001", "用户一的消息")
        );
        ConversationState second = store.getOrCreate(
                new ChatRequest("shared-conv", "USER-002", "用户二的消息")
        );

        assertNotSame(first, second);
        assertEquals("USER-001", store.get(firstKey).getUserId());
        assertEquals("USER-002", store.get(secondKey).getUserId());
        assertEquals("用户一的消息", store.get(firstKey).getMessages().get(0).get("content"));
        assertEquals("用户二的消息", store.get(secondKey).getMessages().get(0).get("content"));

        // 清除用户一的同名会话，不能连带删除用户二的状态。
        assertTrue(store.clear(firstKey));
        assertEquals(null, store.get(firstKey));
        assertEquals("USER-002", store.get(secondKey).getUserId());
    }

    @Test
    void shouldIsolateSameUserAndConversationAcrossTenants() {
        ConversationStore store = new ConversationStore();
        ConversationKey tenantAKey = new ConversationKey("tenant-a", "USER-001", "shared-conv");
        ConversationKey tenantBKey = new ConversationKey("tenant-b", "USER-001", "shared-conv");

        store.getOrCreate(tenantRequest("tenant-a", "tenant A 的消息"));
        store.getOrCreate(tenantRequest("tenant-b", "tenant B 的消息"));

        assertEquals("tenant A 的消息", store.get(tenantAKey).getMessages().get(0).get("content"));
        assertEquals("tenant B 的消息", store.get(tenantBKey).getMessages().get(0).get("content"));
        assertTrue(store.clear(tenantAKey));
        assertFalse(store.clear(tenantAKey));
        assertEquals("tenant B 的消息", store.get(tenantBKey).getMessages().get(0).get("content"));
    }

    @Test
    void shouldRejectSavingStateUnderAnotherUsersKey() {
        ConversationStore store = new ConversationStore();
        ConversationState state = new ConversationState("我的消息", "USER-001");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> store.save(ConversationKey.defaultTenant("USER-002", "conv-owner"), state)
        );

        assertTrue(error.getMessage().contains("owner does not match"));
    }

    @Test
    void shouldSerializeOperationsForSameConversation() throws Exception {
        ConversationStore store = new ConversationStore();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        try {
            Future<String> first = executor.submit(() -> store.executeSerially(
                    new ChatRequest("serial-conv", "USER-001", "first"),
                    state -> {
                        int now = running.incrementAndGet();
                        maxRunning.accumulateAndGet(now, Math::max);
                        firstEntered.countDown();
                        await(releaseFirst);
                        running.decrementAndGet();
                        return "first-done";
                    }
            ));

            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> {
                secondAttempted.countDown();
                return store.executeSerially(
                        new ChatRequest("serial-conv", "USER-001", "second"),
                        state -> {
                            int now = running.incrementAndGet();
                            maxRunning.accumulateAndGet(now, Math::max);
                            running.decrementAndGet();
                            return "second-done";
                        }
                );
            });

            assertTrue(secondAttempted.await(2, TimeUnit.SECONDS));
            releaseFirst.countDown();

            assertEquals("first-done", first.get(2, TimeUnit.SECONDS));
            assertEquals("second-done", second.get(2, TimeUnit.SECONDS));
            assertEquals(1, maxRunning.get(), "同一会话的 operation 不应同时进入临界区");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotShareLockAcrossDifferentOwnersWithSameConversationId() throws Exception {
        ConversationStore store = new ConversationStore();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> store.executeSerially(
                    new ChatRequest("shared-lock-name", "USER-001", "first"),
                    state -> {
                        firstEntered.countDown();
                        await(release);
                        return "first-done";
                    }
            ));

            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> store.executeSerially(
                    new ChatRequest("shared-lock-name", "USER-002", "second"),
                    state -> {
                        secondEntered.countDown();
                        await(release);
                        return "second-done";
                    }
            ));

            // 如果锁仍然只按 conversationId 建立，第二个 owner 会卡在这里直到 release。
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("first-done", first.get(2, TimeUnit.SECONDS));
            assertEquals("second-done", second.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldClearOnlyAfterRunningTurnFinishesSaving() throws Exception {
        ConversationStore store = new ConversationStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "clear-conv");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch clearReturned = new CountDownLatch(1);

        try {
            Future<String> runningTurn = executor.submit(() -> store.executeSerially(
                    new ChatRequest("clear-conv", "USER-001", "正在处理"),
                    state -> {
                        operationEntered.countDown();
                        await(releaseOperation);
                        return "done";
                    }
            ));
            assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

            Future<Boolean> clear = executor.submit(() -> {
                try {
                    return store.clear(key);
                } finally {
                    clearReturned.countDown();
                }
            });

            // 正在运行的一轮尚未保存完成时，clear 不能越过同一把会话锁。
            assertFalse(clearReturned.await(100, TimeUnit.MILLISECONDS));
            releaseOperation.countDown();

            assertEquals("done", runningTurn.get(2, TimeUnit.SECONDS));
            assertTrue(clear.get(2, TimeUnit.SECONDS));
            assertEquals(null, store.get(key));
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    private ChatRequest tenantRequest(String tenantId, String message) {
        return new ChatRequest(
                tenantId,
                "shared-conv",
                "USER-001",
                message,
                AuthenticatedPrincipal.unverifiedClaim("USER-001")
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
