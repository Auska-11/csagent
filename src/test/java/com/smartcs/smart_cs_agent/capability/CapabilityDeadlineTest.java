package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.core.CapabilityInvocationExecutor;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.idempotency.memory.InMemoryCapabilityIdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capability 执行中 deadline 的专项测试。
 *
 * <p>这里故意使用会阻塞的 Fake Provider 做故障注入，验证的不只是“调用前发现 deadline 已过期”，
 * 还包括 Provider 已经开始、同 key 正在等待和任务仍在执行器队列中的三种真实并发阶段。</p>
 */
class CapabilityDeadlineTest {

    private static final String READ_CAPABILITY = "test.deadline.read";
    private static final String SIDE_EFFECT_CAPABILITY = "test.deadline.side-effect";

    @Test
    void shouldEnforceDefinitionTimeoutAfterReadOnlyProviderStarts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CapabilityProvider provider = blockingReadProvider(80, entered, interrupted);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(provider));

        long startedAt = System.nanoTime();
        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall(READ_CAPABILITY, Map.of()),
                readContext(Instant.now().plusSeconds(5))
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, result.getOutcome());
        assertEquals("CAPABILITY_DEADLINE_EXCEEDED", result.getErrorCode());
        assertTrue(elapsedMs < 1_500, "Gateway 应按 80ms 契约返回，不能等待 Fake Provider 的 5 秒");
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldUseEarlierUpstreamDeadlineInsteadOfLongerDefinitionTimeout() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CapabilityProvider provider = blockingReadProvider(5_000, entered, interrupted);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(provider));

        long startedAt = System.nanoTime();
        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall(READ_CAPABILITY, Map.of()),
                readContext(Instant.now().plusMillis(80))
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertEquals("CAPABILITY_DEADLINE_EXCEEDED", result.getErrorCode());
        assertTrue(elapsedMs < 1_500, "上游 80ms deadline 必须优先于能力自己的 5 秒默认值");
    }

    @Test
    void shouldPropagateNarrowedDeadlineToProvider() {
        AtomicReference<ExecutionContext> received = new AtomicReference<>();
        Instant beforeInvoke = Instant.now();
        Instant outerDeadline = beforeInvoke.plusSeconds(5);
        CapabilityProvider provider = immediateReadProvider(120, received);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(provider));

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall(READ_CAPABILITY, Map.of()),
                readContext(outerDeadline)
        );

        assertTrue(result.isSuccess());
        assertNotNull(received.get());
        assertTrue(received.get().getDeadline().isBefore(outerDeadline));
        assertFalse(received.get().getDeadline().isBefore(beforeInvoke));
        assertEquals("tenant-deadline", received.get().getTenantId());
        assertEquals("conversation-deadline", received.get().getConversationId());
    }

    @Test
    void shouldPersistUnknownSideEffectResultAndReplayWithoutSecondInvocation() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        CapabilityProvider provider = blockingSideEffectProvider(80, invocations, null, interrupted);
        InMemoryCapabilityIdempotencyStore store = new InMemoryCapabilityIdempotencyStore();
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(provider), store);
        CapabilityCall call = new CapabilityCall(SIDE_EFFECT_CAPABILITY, Map.of("value", "same"));

        CapabilityResult<?> first = gateway.invoke(call, sideEffectContext("deadline-unknown", 5_000));
        CapabilityResult<?> replay = gateway.invoke(call, sideEffectContext("deadline-unknown", 5_000));

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, first.getOutcome());
        assertEquals("CAPABILITY_RESULT_UNKNOWN", first.getErrorCode());
        assertFalse(first.isReplayed());
        assertEquals("CAPABILITY_RESULT_UNKNOWN", replay.getErrorCode());
        assertTrue(replay.isReplayed());
        assertEquals(1, invocations.get());
        assertEquals(1, store.size());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldBoundWaitingForExistingIdempotentExecutionByCallerDeadline() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CapabilityProvider provider = blockingSideEffectProvider(5_000, invocations, entered, release);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                List.of(provider),
                new InMemoryCapabilityIdempotencyStore()
        );
        CapabilityCall call = new CapabilityCall(SIDE_EFFECT_CAPABILITY, Map.of("value", "same"));
        ExecutorService callers = Executors.newSingleThreadExecutor();

        try {
            Future<CapabilityResult<?>> owner = callers.submit(
                    () -> gateway.invoke(call, sideEffectContext("deadline-wait", 5_000))
            );
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            long startedAt = System.nanoTime();
            CapabilityResult<?> waiting = gateway.invoke(
                    call,
                    sideEffectContext("deadline-wait", 80)
            );
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals("CAPABILITY_DEADLINE_EXCEEDED", waiting.getErrorCode());
            assertTrue(elapsedMs < 1_500);
            assertEquals(1, invocations.get());

            release.countDown();
            assertTrue(owner.get(2, TimeUnit.SECONDS).isSuccess());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void shouldReleaseIdempotencyClaimWhenQueuedProviderNeverStarted() throws Exception {
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger sideEffectInvocations = new AtomicInteger();
        CapabilityProvider blocker = blockingReadProvider(5_000, blockerEntered, releaseBlocker);
        CapabilityProvider sideEffect = immediateSideEffectProvider(80, sideEffectInvocations);
        InMemoryCapabilityIdempotencyStore store = new InMemoryCapabilityIdempotencyStore();
        CapabilityInvocationExecutor invocationExecutor = new CapabilityInvocationExecutor(1, 1);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                List.of(blocker, sideEffect),
                store,
                invocationExecutor
        );
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CapabilityCall sideEffectCall = new CapabilityCall(
                SIDE_EFFECT_CAPABILITY,
                Map.of("value", "queued")
        );

        try {
            Future<CapabilityResult<?>> blockerResult = callers.submit(
                    () -> gateway.invoke(
                            new CapabilityCall(READ_CAPABILITY, Map.of()),
                            readContext(Instant.now().plusSeconds(5))
                    )
            );
            assertTrue(blockerEntered.await(1, TimeUnit.SECONDS));

            // 唯一工作线程被 blocker 占用，副作用任务只能排队，并在真正开始前到达 80ms deadline。
            CapabilityResult<?> queuedTimeout = gateway.invoke(
                    sideEffectCall,
                    sideEffectContext("queued-not-started", 5_000)
            );
            assertEquals("CAPABILITY_DEADLINE_EXCEEDED", queuedTimeout.getErrorCode());
            assertEquals(0, sideEffectInvocations.get());
            assertEquals(0, store.size(), "Provider 未开始时必须释放幂等占位");

            releaseBlocker.countDown();
            assertTrue(blockerResult.get(2, TimeUnit.SECONDS).isSuccess());

            CapabilityResult<?> retry = gateway.invoke(
                    sideEffectCall,
                    sideEffectContext("queued-not-started", 5_000)
            );
            assertTrue(retry.isSuccess());
            assertEquals(1, sideEffectInvocations.get());
        } finally {
            releaseBlocker.countDown();
            callers.shutdownNow();
            invocationExecutor.shutdown();
        }
    }

    @Test
    void shouldFailFastWithoutStartingProviderWhenExecutorIsSaturated() throws Exception {
        CapabilityInvocationExecutor invocationExecutor = new CapabilityInvocationExecutor(1, 1);
        CountDownLatch runningTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseRunningTask = new CountDownLatch(1);
        AtomicReference<ExecutionContext> providerContext = new AtomicReference<>();

        try {
            Future<Void> running = invocationExecutor.submit(() -> {
                runningTaskEntered.countDown();
                releaseRunningTask.await(5, TimeUnit.SECONDS);
                return null;
            });
            assertTrue(runningTaskEntered.await(1, TimeUnit.SECONDS));

            // maxThreads=1 且 queueCapacity=1：一个任务运行、一个任务排队后，第三个提交必须被拒绝。
            Future<Void> queued = invocationExecutor.submit(() -> null);
            DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                    List.of(immediateReadProvider(5_000, providerContext)),
                    new InMemoryCapabilityIdempotencyStore(),
                    invocationExecutor
            );

            CapabilityResult<?> result = gateway.invoke(
                    new CapabilityCall(READ_CAPABILITY, Map.of()),
                    readContext(Instant.now().plusSeconds(5))
            );

            assertEquals("CAPABILITY_EXECUTOR_SATURATED", result.getErrorCode());
            assertEquals(null, providerContext.get());

            queued.cancel(true);
            releaseRunningTask.countDown();
            running.get(2, TimeUnit.SECONDS);
        } finally {
            releaseRunningTask.countDown();
            invocationExecutor.shutdown();
        }
    }

    private CapabilityProvider blockingReadProvider(long timeoutMs,
                                                      CountDownLatch entered,
                                                      CountDownLatch interruptedOrRelease) {
        return provider(
                "blocking-read-provider",
                readDefinition(timeoutMs),
                (call, context) -> {
                    entered.countDown();
                    try {
                        if (!interruptedOrRelease.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Fake provider was not released");
                        }
                    } catch (InterruptedException e) {
                        interruptedOrRelease.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return CapabilityResult.success(call.getCapabilityId(), "blocking-read-provider", "ok", Map.of());
                }
        );
    }

    private CapabilityProvider immediateReadProvider(long timeoutMs,
                                                     AtomicReference<ExecutionContext> received) {
        return provider(
                "immediate-read-provider",
                readDefinition(timeoutMs),
                (call, context) -> {
                    received.set(context);
                    return CapabilityResult.success(call.getCapabilityId(), "immediate-read-provider", "ok", Map.of());
                }
        );
    }

    private CapabilityProvider blockingSideEffectProvider(long timeoutMs,
                                                           AtomicInteger invocations,
                                                           CountDownLatch entered,
                                                           CountDownLatch interruptedOrRelease) {
        return provider(
                "blocking-side-effect-provider",
                sideEffectDefinition(timeoutMs),
                (call, context) -> {
                    invocations.incrementAndGet();
                    if (entered != null) {
                        entered.countDown();
                    }
                    try {
                        if (!interruptedOrRelease.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Fake side effect was not released");
                        }
                    } catch (InterruptedException e) {
                        interruptedOrRelease.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return CapabilityResult.success(
                            call.getCapabilityId(),
                            "blocking-side-effect-provider",
                            "submitted",
                            Map.of()
                    );
                }
        );
    }

    private CapabilityProvider immediateSideEffectProvider(long timeoutMs,
                                                           AtomicInteger invocations) {
        return provider(
                "immediate-side-effect-provider",
                sideEffectDefinition(timeoutMs),
                (call, context) -> {
                    invocations.incrementAndGet();
                    return CapabilityResult.success(
                            call.getCapabilityId(),
                            "immediate-side-effect-provider",
                            "submitted",
                            Map.of()
                    );
                }
        );
    }

    private CapabilityProvider provider(String providerId,
                                        CapabilityDefinition definition,
                                        Invocation invocation) {
        return new CapabilityProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public Collection<CapabilityDefinition> definitions() {
                return List.of(definition);
            }

            @Override
            public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
                return invocation.invoke(call, context);
            }
        };
    }

    private CapabilityDefinition readDefinition(long timeoutMs) {
        return new CapabilityDefinition(
                READ_CAPABILITY,
                "1.0.0",
                "deadline 只读测试能力",
                Map.of("type", "object"),
                Map.of("type", "object"),
                false,
                false,
                false,
                false,
                timeoutMs,
                CapabilityRiskLevel.READ_ONLY
        );
    }

    private CapabilityDefinition sideEffectDefinition(long timeoutMs) {
        return new CapabilityDefinition(
                SIDE_EFFECT_CAPABILITY,
                "1.0.0",
                "deadline 副作用测试能力",
                Map.of("type", "object"),
                Map.of("type", "object"),
                true,
                true,
                true,
                true,
                timeoutMs,
                CapabilityRiskLevel.HIGH
        );
    }

    private ExecutionContext readContext(Instant deadline) {
        return new ExecutionContext(
                "trace-deadline",
                AuthenticatedPrincipal.anonymous(),
                "tenant-deadline",
                "conversation-deadline",
                "",
                deadline
        );
    }

    private ExecutionContext sideEffectContext(String idempotencyKey, long outerTimeoutMs) {
        return new ExecutionContext(
                "trace-deadline",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                "tenant-deadline",
                "conversation-deadline",
                idempotencyKey,
                Instant.now().plusMillis(outerTimeoutMs)
        );
    }

    @FunctionalInterface
    private interface Invocation {
        CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context);
    }
}
