package com.smartcs.smart_cs_agent.agent.llm;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM wall-clock Guard 的故障注入测试。
 *
 * <p>这里使用会睡眠的 Fake Model，不访问真实网络。测试关注的是等待上限、取消信号和多次调用
 * 是否共享 Turn 余额，而不是某个模型厂商 SDK 的具体实现。</p>
 */
class LlmExecutionGuardTest {

    @Test
    void shouldReturnAtCallDeadlineAndRequestModelInterruption() throws Exception {
        LlmExecutionGuard guard = new LlmExecutionGuard(300, 1, 1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            long startedAtNanos = System.nanoTime();
            Future<LlmExecutionGuard.LlmInvocationResult> invocation = caller.submit(
                    () -> guard.generate(
                            sleepingModel(5_000, started, interrupted, new AtomicInteger()),
                            messages(),
                            turnContext()
                    )
            );
            assertTrue(started.await(1, TimeUnit.SECONDS), "先确认模型任务已经真正开始");
            LlmExecutionGuard.LlmInvocationResult result = invocation.get(2, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertFalse(result.success());
            assertEquals("LLM_CALL_DEADLINE_EXCEEDED", result.errorCode());
            assertEquals(300, result.budgetMs());
            assertTrue(elapsedMs < 1_000, "调用方不应继续等待 Fake Model 的 5 秒睡眠");
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "cancel(true) 应向模型线程请求中断");
        } finally {
            caller.shutdownNow();
            guard.shutdown();
        }
    }

    @Test
    void shouldUseRemainingTurnBudgetAcrossMultipleModelCalls() {
        LlmExecutionGuard guard = new LlmExecutionGuard(1_000, 1, 1);
        AtomicInteger invocationCount = new AtomicInteger();
        TurnContext turn = turnContext();
        turn.startTurnBudget(1_500);
        try {
            ChatLanguageModel model = sleepingModel(
                    900,
                    new CountDownLatch(0),
                    new CountDownLatch(1),
                    invocationCount
            );

            LlmExecutionGuard.LlmInvocationResult first = guard.generate(model, messages(), turn);
            LlmExecutionGuard.LlmInvocationResult second = guard.generate(model, messages(), turn);

            assertTrue(first.success(), "第一次调用应在 Turn 预算内完成");
            assertFalse(second.success(), "第二次调用只能使用第一次调用后剩余的预算");
            assertEquals("LLM_TURN_BUDGET_EXCEEDED", second.errorCode());
            assertTrue(second.budgetMs() < 1_000);
            assertEquals(2, invocationCount.get());
        } finally {
            guard.shutdown();
        }
    }

    @Test
    void shouldNotExposeProviderExceptionDetails() {
        LlmExecutionGuard guard = new LlmExecutionGuard(200, 1, 1);
        try {
            ChatLanguageModel failingModel = new ChatLanguageModel() {
                @Override
                public Response<AiMessage> generate(List<ChatMessage> messages) {
                    throw new IllegalStateException("token=secret-provider-detail");
                }
            };

            LlmExecutionGuard.LlmInvocationResult result = guard.generate(
                    failingModel,
                    messages(),
                    turnContext()
            );

            assertFalse(result.success());
            assertEquals("LLM_PROVIDER_ERROR", result.errorCode());
            assertNull(result.text());
        } finally {
            guard.shutdown();
        }
    }

    @Test
    void shouldTreatLangChainMaxRetriesValueAsTotalAttemptLimit() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> RetryUtils.withRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("retry-semantics-test");
        }, 1));

        // LangChain4j 0.35 的参数名称容易误导：传 1 是总共尝试一次，而不是额外重试一次。
        assertEquals(1, attempts.get());
    }

    @Test
    void shouldNotStartModelWhenTurnBudgetIsAlreadyExhausted() throws Exception {
        LlmExecutionGuard guard = new LlmExecutionGuard(1_000, 1, 1);
        AtomicInteger invocationCount = new AtomicInteger();
        TurnContext turn = turnContext();
        turn.startTurnBudget(1);
        Thread.sleep(10);
        try {
            LlmExecutionGuard.LlmInvocationResult result = guard.generate(
                    sleepingModel(
                            0,
                            new CountDownLatch(0),
                            new CountDownLatch(0),
                            invocationCount
                    ),
                    messages(),
                    turn
            );

            assertFalse(result.success());
            assertEquals("LLM_TURN_BUDGET_EXCEEDED", result.errorCode());
            assertEquals(0, invocationCount.get(), "预算耗尽后不能把模型任务提交到执行器");
        } finally {
            guard.shutdown();
        }
    }

    @Test
    void shouldRejectNewInvocationWhenThreadAndQueueAreBothFull() throws Exception {
        LlmExecutionGuard guard = new LlmExecutionGuard(5_000, 1, 1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            ChatLanguageModel blockingModel = new ChatLanguageModel() {
                @Override
                public Response<AiMessage> generate(List<ChatMessage> messages) {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return Response.from(AiMessage.from("first"));
                }
            };
            ChatLanguageModel fastModel = messages -> Response.from(AiMessage.from("queued"));

            Future<LlmExecutionGuard.LlmInvocationResult> first = callers.submit(
                    () -> guard.generate(blockingModel, messages(), turnContext())
            );
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            Future<LlmExecutionGuard.LlmInvocationResult> queued = callers.submit(
                    () -> guard.generate(fastModel, messages(), turnContext())
            );
            awaitCondition(() -> guard.queuedInvocationCount() == 1, 1_000);

            LlmExecutionGuard.LlmInvocationResult saturated = guard.generate(
                    fastModel,
                    messages(),
                    turnContext()
            );

            assertFalse(saturated.success());
            assertEquals("LLM_EXECUTOR_SATURATED", saturated.errorCode());

            releaseFirst.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).success());
            assertTrue(queued.get(2, TimeUnit.SECONDS).success());
        } finally {
            releaseFirst.countDown();
            callers.shutdownNow();
            guard.shutdown();
        }
    }

    private ChatLanguageModel sleepingModel(long sleepMs,
                                            CountDownLatch started,
                                            CountDownLatch interrupted,
                                            AtomicInteger invocationCount) {
        return new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                invocationCount.incrementAndGet();
                started.countDown();
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException cancellationRequested) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return Response.from(AiMessage.from("fake response"));
            }
        };
    }

    private List<ChatMessage> messages() {
        return List.of(UserMessage.from("test"));
    }

    private void awaitCondition(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "等待执行器进入预期状态超时");
    }

    private TurnContext turnContext() {
        ChatRequest request = new ChatRequest("conv-llm-budget", "USER-001", "测试模型预算");
        return new TurnContext(
                request,
                new ConversationState(request.getMessage(), request.getUserId())
        );
    }
}
