package com.smartcs.smart_cs_agent.capability.idempotency.memory;

import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.api.CapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyWaitDeadlineExceededException;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyKeyConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 单进程幂等实现，适合测试、本地学习和 memory 模式。
 *
 * <p>同一个 key 的并发调用共享一个 CompletableFuture：只有第一个线程进入 Provider，其他线程
 * 等待并回放结果。它不能跨 JVM 协调，所以 jdbc 模式必须使用数据库实现。</p>
 */
@Component
@ConditionalOnProperty(
        name = "smartcs.persistence.mode",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryCapabilityIdempotencyStore implements CapabilityIdempotencyStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public CapabilityResult<?> execute(String storageKey,
                                       String requestFingerprint,
                                       Supplier<CapabilityResult<?>> operation) {
        return execute(storageKey, requestFingerprint, operation, null);
    }

    @Override
    public CapabilityResult<?> execute(String storageKey,
                                       String requestFingerprint,
                                       Supplier<CapabilityResult<?>> operation,
                                       Instant deadline) {
        Entry candidate = new Entry(requestFingerprint);
        Entry existing = entries.putIfAbsent(storageKey, candidate);

        if (existing != null) {
            if (!existing.requestFingerprint.equals(requestFingerprint)) {
                throw new IdempotencyKeyConflictException(storageKey);
            }
            return awaitExisting(existing, storageKey, deadline).asReplayed();
        }

        try {
            CapabilityResult<?> result = operation.get();
            candidate.result.complete(result);

            // Provider 一旦开始执行，技术失败也可能表示“副作用已完成，但响应丢失”。
            // 因此三种 outcome 都稳定回放，不能因为看到 TECHNICAL_FAILURE 就自动重复执行。
            return result;
        } catch (RuntimeException e) {
            candidate.result.completeExceptionally(e);
            entries.remove(storageKey, candidate);
            throw e;
        }
    }

    public int size() {
        return entries.size();
    }

    private CapabilityResult<?> awaitExisting(Entry existing,
                                               String storageKey,
                                               Instant deadline) {
        if (deadline == null) {
            try {
                return existing.result.join();
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        }

        long remainingNanos = Duration.between(Instant.now(), deadline).toNanos();
        if (remainingNanos <= 0) {
            throw new IdempotencyWaitDeadlineExceededException(storageKey);
        }
        try {
            return existing.result.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new IdempotencyWaitDeadlineExceededException(storageKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for idempotent result", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Idempotent operation failed", cause);
        }
    }

    private RuntimeException unwrap(CompletionException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof RuntimeException runtime ? runtime : exception;
    }

    private static final class Entry {
        private final String requestFingerprint;
        private final CompletableFuture<CapabilityResult<?>> result = new CompletableFuture<>();

        private Entry(String requestFingerprint) {
            this.requestFingerprint = requestFingerprint;
        }
    }
}
