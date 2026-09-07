package com.smartcs.smart_cs_agent.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话串行锁的排队预算与快速失败语义。
 *
 * <p>缺陷背景：旧实现先无超时 lock.lock()、拿到锁之后 Runtime 才开始计 30 秒预算——
 * 同一会话连续提交慢请求时，后续请求总耗时可能远超预算。现在预算从进入 executeSerially
 * 就开始计时，排队等待消耗预算，超时抛 {@link ConversationBusyException} 快速失败。</p>
 */
class ConversationStoreBudgetTest {

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void operationShouldReceiveRemainingBudgetAfterQueueing() {
        ConversationStore store = new ConversationStore();
        ChatRequest request = new ChatRequest("conv-budget", "user-budget", "你好");

        long budgetMs = 500;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        AtomicLong observedRemaining = new AtomicLong();

        store.executeSerially(request, deadlineNanos, (state, remainingBudgetMs) -> {
            observedRemaining.set(remainingBudgetMs);
            return "ok";
        });

        assertTrue(observedRemaining.get() > 0, "剩余预算必须为正数（startTurnBudget 拒绝 <=0）");
        assertTrue(observedRemaining.get() <= budgetMs, "剩余预算不能超过总预算");
    }

    @Test
    void queuedRequestBeyondDeadlineShouldFailFastWithoutSideEffects() throws Exception {
        ConversationStore store = new ConversationStore();
        ChatRequest request = new ChatRequest("conv-busy", "user-busy", "你好");
        CountDownLatch firstTurnEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstTurn = new CountDownLatch(1);
        AtomicReference<Object> secondTurnSideEffect = new AtomicReference<>();
        executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> store.executeSerially(
                    request,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(30),
                    (state, remainingBudgetMs) -> {
                        firstTurnEntered.countDown();
                        await(releaseFirstTurn);
                        return "first";
                    }
            ));
            assertTrue(firstTurnEntered.await(2, TimeUnit.SECONDS), "第一轮应先进入执行");

            // 第二轮只在 150ms 预算内排队：锁被第一轮持有，必须在预算耗尽时快速失败，
            // 且 operation 不能被调用（无副作用）。
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
            ConversationBusyException busy = assertThrows(ConversationBusyException.class,
                    () -> store.executeSerially(request, deadlineNanos, (state, remainingBudgetMs) -> {
                        secondTurnSideEffect.set("must-not-run");
                        return "second";
                    }));

            assertEquals(ConversationKey.from(request), busy.getConversationKey());
            assertEquals(null, secondTurnSideEffect.get());

            releaseFirstTurn.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirstTurn.countDown();
        }
    }

    @Test
    void expiredDeadlineShouldFailFastWithoutQueueing() {
        ConversationStore store = new ConversationStore();
        ChatRequest request = new ChatRequest("conv-expired", "user-expired", "你好");

        // deadline 已经过去：即使锁空闲也必须立刻失败，不能重新变成无限等待。
        assertThrows(ConversationBusyException.class, () -> store.executeSerially(
                request,
                System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1),
                (state, remainingBudgetMs) -> "must-not-run"
        ));
    }

    @Test
    void legacyOverloadShouldStillExecuteAndSave() {
        ConversationStore store = new ConversationStore();
        ChatRequest request = new ChatRequest("conv-legacy", "user-legacy", "你好");

        String reply = store.executeSerially(request, state -> "legacy-ok");
        ConversationState restored = store.get(ConversationKey.from(request));

        assertEquals("legacy-ok", reply);
        assertNotNull(restored, "兼容入口执行后必须保存会话状态");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }
}
