package com.smartcs.smart_cs_agent.capability.core;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capability 的执行时间与资源 Guardrail。
 *
 * <p>它只处理通用的 deadline、线程池饱和、线程中断和“副作用结果未知”分类，不负责选择 Provider、
 * 校验身份、生成幂等 key 或理解任何订单/退货业务。把这些机制从 Gateway 拆出后，后续替换执行器或
 * 加指标时不需要继续放大路由类。</p>
 */
@Component
public class CapabilityExecutionGuard {

    private final CapabilityInvocationExecutor invocationExecutor;

    public CapabilityExecutionGuard(CapabilityInvocationExecutor invocationExecutor) {
        this.invocationExecutor = Objects.requireNonNull(
                invocationExecutor,
                "invocationExecutor cannot be null"
        );
    }

    /** 上游预算和能力默认预算只能收紧，不能互相突破。 */
    public ExecutionContext boundExecutionContext(CapabilityDefinition definition,
                                                   ExecutionContext context) {
        Instant capabilityDeadline = Instant.now().plusMillis(definition.getDefaultTimeoutMs());
        Instant upstreamDeadline = context.getDeadline();
        if (upstreamDeadline != null && !capabilityDeadline.isBefore(upstreamDeadline)) {
            return context;
        }
        return context.withDeadline(capabilityDeadline);
    }

    public boolean deadlineReached(ExecutionContext context) {
        Instant deadline = context.getDeadline();
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /**
     * 在有界线程池中执行已经由 Gateway 包装好的 Provider operation。
     *
     * <p>operation 仍负责 Provider 返回值契约校验；本类只决定等待多久以及超时属于“确定未开始”还是
     * “开始后结果未知”。Future.cancel(true) 只是请求中断，不能证明远端事务已经停止。</p>
     */
    public ProviderExecution invoke(CapabilityDefinition definition,
                                    CapabilityProvider provider,
                                    CapabilityCall call,
                                    ExecutionContext context,
                                    Callable<CapabilityResult<?>> operation) {
        long remainingNanos = remainingNanos(context.getDeadline());
        if (remainingNanos <= 0) {
            return new ProviderExecution(deadlineFailure(definition, provider, false), false);
        }

        AtomicBoolean providerStarted = new AtomicBoolean(false);
        Future<CapabilityResult<?>> future;
        try {
            future = invocationExecutor.submit(() -> {
                providerStarted.set(true);
                return operation.call();
            });
        } catch (RejectedExecutionException saturated) {
            return new ProviderExecution(
                    CapabilityResult.technicalFailure(
                            call.getCapabilityId(),
                            provider.providerId(),
                            "CAPABILITY_EXECUTOR_SATURATED",
                            "Capability executor is temporarily saturated"
                    ),
                    false
            );
        }

        try {
            return new ProviderExecution(
                    future.get(remainingNanos, TimeUnit.NANOSECONDS),
                    true
            );
        } catch (TimeoutException timeout) {
            future.cancel(true);
            boolean started = providerStarted.get();
            return new ProviderExecution(deadlineFailure(definition, provider, started), started);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            boolean started = providerStarted.get();
            return new ProviderExecution(interruptedFailure(definition, provider, started), started);
        } catch (CancellationException cancelled) {
            boolean started = providerStarted.get();
            return new ProviderExecution(interruptedFailure(definition, provider, started), started);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            boolean started = providerStarted.get();
            return new ProviderExecution(
                    CapabilityResult.technicalFailure(
                            call.getCapabilityId(),
                            provider.providerId(),
                            "CAPABILITY_PROVIDER_ERROR",
                            "Capability provider failed: "
                                    + (cause == null ? "Unknown" : cause.getClass().getSimpleName())
                    ),
                    started
            );
        }
    }

    private long remainingNanos(Instant deadline) {
        if (deadline == null) {
            return 0;
        }
        return Duration.between(Instant.now(), deadline).toNanos();
    }

    private CapabilityResult<?> deadlineFailure(CapabilityDefinition definition,
                                                CapabilityProvider provider,
                                                boolean providerStarted) {
        if (definition.isSideEffect() && providerStarted) {
            return resultUnknown(definition, provider);
        }
        return CapabilityResult.technicalFailure(
                definition.getId(),
                provider.providerId(),
                "CAPABILITY_DEADLINE_EXCEEDED",
                "Capability execution exceeded its deadline"
        );
    }

    private CapabilityResult<?> interruptedFailure(CapabilityDefinition definition,
                                                   CapabilityProvider provider,
                                                   boolean providerStarted) {
        if (definition.isSideEffect() && providerStarted) {
            return resultUnknown(definition, provider);
        }
        return CapabilityResult.technicalFailure(
                definition.getId(),
                provider.providerId(),
                "CAPABILITY_EXECUTION_INTERRUPTED",
                "Capability execution was interrupted"
        );
    }

    private CapabilityResult<?> resultUnknown(CapabilityDefinition definition,
                                              CapabilityProvider provider) {
        return CapabilityResult.technicalFailure(
                definition.getId(),
                provider.providerId(),
                "CAPABILITY_RESULT_UNKNOWN",
                "Capability may have started before timeout; query or review the result before retrying"
        );
    }

    /** 手动 new Gateway 的测试共用同一个 Guard 和进程级 daemon 执行器。 */
    static CapabilityExecutionGuard sharedDefault() {
        return SharedDefaultHolder.INSTANCE;
    }

    private static final class SharedDefaultHolder {
        private static final CapabilityExecutionGuard INSTANCE =
                new CapabilityExecutionGuard(CapabilityInvocationExecutor.sharedDefault());
    }

    /** providerStarted=false 时，幂等 Store 可以确定没有副作用并安全释放占位。 */
    public record ProviderExecution(CapabilityResult<?> result, boolean providerStarted) {
    }
}
