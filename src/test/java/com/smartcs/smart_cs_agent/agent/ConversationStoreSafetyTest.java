package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话归属与同会话串行化安全测试。 */
class ConversationStoreSafetyTest {

    @Test
    void shouldRejectSameConversationIdFromDifferentUser() {
        ConversationStore store = new ConversationStore();
        store.getOrCreate(new ChatRequest("shared-conv", "USER-001", "第一条消息"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> store.getOrCreate(new ChatRequest("shared-conv", "USER-002", "越权消息"))
        );

        assertTrue(error.getMessage().contains("belongs to another user"));
        assertEquals(1, store.get("shared-conv").getMessages().size());
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
