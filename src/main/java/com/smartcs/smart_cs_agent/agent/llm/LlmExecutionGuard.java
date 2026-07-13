package com.smartcs.smart_cs_agent.agent.llm;

import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 调用的 wall-clock 与资源 Guardrail。
 *
 * <p>LangChain4j 负责一次 OpenAI 调用内部的 HTTP 和有限重试；本类不再实现第二套重试，只负责：
 * 单次 LLM Action 最多等多久、多次 Action 不能突破 Turn 总预算，以及模型卡住时不能无限创建线程。</p>
 *
 * <p>LLM 生成是只读操作，超时后可以丢弃迟到结果。cancel(true) 仍只是请求中断，所以执行器必须有
 * 线程和队列上限；即使某个模型 SDK 忽略中断，遗留任务数量也会被限制。</p>
 */
@Component
public class LlmExecutionGuard {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final long callTimeoutNanos;
    private final ThreadPoolExecutor executor;

    public LlmExecutionGuard(
            @Value("${smartcs.llm.execution.call-timeout-ms:12000}") long callTimeoutMs,
            @Value("${smartcs.llm.execution.max-threads:8}") int maxThreads,
            @Value("${smartcs.llm.execution.queue-capacity:32}") int queueCapacity) {
        if (callTimeoutMs <= 0) {
            throw new IllegalArgumentException("LLM call-timeout-ms must be positive");
        }
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("LLM max-threads must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("LLM queue-capacity must be positive");
        }
        this.callTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(callTimeoutMs);
        this.executor = new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "llm-call-" + THREAD_SEQUENCE.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
    }

    public LlmInvocationResult generate(ChatLanguageModel chatModel,
                                        List<ChatMessage> messages,
                                        TurnContext turnContext) {
        Objects.requireNonNull(chatModel, "chatModel cannot be null");
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(turnContext, "turnContext cannot be null");

        long remainingTurnNanos = turnContext.remainingTurnBudgetNanos();
        boolean limitedByTurn = remainingTurnNanos <= callTimeoutNanos;
        long effectiveBudgetNanos = Math.min(callTimeoutNanos, remainingTurnNanos);
        long startedAtNanos = System.nanoTime();
        if (effectiveBudgetNanos <= 0) {
            return failure(
                    "LLM_TURN_BUDGET_EXCEEDED",
                    startedAtNanos,
                    effectiveBudgetNanos
            );
        }

        Future<Response<AiMessage>> future;
        try {
            // 复制 List 结构，避免调用线程在模型线程读取期间修改消息顺序。
            List<ChatMessage> immutableMessages = List.copyOf(messages);
            future = executor.submit(() -> chatModel.generate(immutableMessages));
        } catch (RejectedExecutionException saturated) {
            return failure("LLM_EXECUTOR_SATURATED", startedAtNanos, effectiveBudgetNanos);
        }

        try {
            Response<AiMessage> response = future.get(effectiveBudgetNanos, TimeUnit.NANOSECONDS);
            if (response == null || response.content() == null) {
                return failure("LLM_EMPTY_RESPONSE", startedAtNanos, effectiveBudgetNanos);
            }
            String text = response.content().text();
            if (text == null || text.isBlank()) {
                return failure("LLM_EMPTY_RESPONSE", startedAtNanos, effectiveBudgetNanos);
            }
            return new LlmInvocationResult(
                    true,
                    text,
                    null,
                    elapsedMillis(startedAtNanos),
                    nanosToCeilingMillis(effectiveBudgetNanos)
            );
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return failure(
                    limitedByTurn ? "LLM_TURN_BUDGET_EXCEEDED" : "LLM_CALL_DEADLINE_EXCEEDED",
                    startedAtNanos,
                    effectiveBudgetNanos
            );
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failure("LLM_EXECUTION_INTERRUPTED", startedAtNanos, effectiveBudgetNanos);
        } catch (CancellationException cancelled) {
            return failure("LLM_EXECUTION_INTERRUPTED", startedAtNanos, effectiveBudgetNanos);
        } catch (ExecutionException executionFailure) {
            // 不把模型 SDK 的异常 message 放进 Trace，避免 URL、Prompt 或响应片段泄露。
            return failure("LLM_PROVIDER_ERROR", startedAtNanos, effectiveBudgetNanos);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 同包故障注入测试使用的最小观察接缝。
     *
     * <p>它不暴露任务、Prompt 或线程对象，只返回当前排队数量。后续接 Micrometer 时也应只采集这类
     * 聚合数字，不能为了监控把模型输入放进指标标签。</p>
     */
    int queuedInvocationCount() {
        return executor.getQueue().size();
    }

    /** 兼容旧纯 Java 测试；生产环境由 Spring 创建独立可配置实例。 */
    public static LlmExecutionGuard sharedDefault() {
        return SharedDefaultHolder.INSTANCE;
    }

    private LlmInvocationResult failure(String errorCode,
                                        long startedAtNanos,
                                        long effectiveBudgetNanos) {
        return new LlmInvocationResult(
                false,
                null,
                errorCode,
                elapsedMillis(startedAtNanos),
                nanosToCeilingMillis(Math.max(0, effectiveBudgetNanos))
        );
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    private long nanosToCeilingMillis(long nanos) {
        if (nanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long wholeMillis = TimeUnit.NANOSECONDS.toMillis(nanos);
        return nanos % TimeUnit.MILLISECONDS.toNanos(1) == 0
                ? wholeMillis
                : wholeMillis + 1;
    }

    private static final class SharedDefaultHolder {
        private static final LlmExecutionGuard INSTANCE = new LlmExecutionGuard(12_000, 8, 32);
    }

    /** Handler 只消费安全结果，不需要理解 Future、线程池或模型 SDK 异常。 */
    public record LlmInvocationResult(
            boolean success,
            String text,
            String errorCode,
            long durationMs,
            long budgetMs
    ) {
    }
}
