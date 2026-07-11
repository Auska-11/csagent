package com.smartcs.smart_cs_agent.capability.core;

import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 副作用 Capability 的进程内幂等记录表。
 *
 * <p>同一个 key 的并发调用会共享一个 CompletableFuture：只有第一个线程进入 Provider，其他线程
 * 等待并回放结果。生产多实例部署时应把此接口替换成 Redis/数据库唯一键实现。</p>
 */
@Component
public class CapabilityIdempotencyStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public CapabilityResult<?> execute(String storageKey,
                                       String requestFingerprint,
                                       Supplier<CapabilityResult<?>> operation) {
        Entry candidate = new Entry(requestFingerprint);
        Entry existing = entries.putIfAbsent(storageKey, candidate);

        if (existing != null) {
            if (!existing.requestFingerprint.equals(requestFingerprint)) {
                throw new IdempotencyKeyConflictException(storageKey);
            }
            try {
                return existing.result.join().asReplayed();
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        }

        try {
            CapabilityResult<?> result = operation.get();
            candidate.result.complete(result);

            // 技术失败通常可重试，不应该永久占住 key；成功和业务拒绝则必须稳定回放。
            if (result.getOutcome() == CapabilityOutcome.TECHNICAL_FAILURE) {
                entries.remove(storageKey, candidate);
            }
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
