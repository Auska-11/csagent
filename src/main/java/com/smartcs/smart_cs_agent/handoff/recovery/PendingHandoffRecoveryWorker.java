package com.smartcs.smart_cs_agent.handoff.recovery;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffClaim;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStore;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketClient;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 后台推进结果未知的人工工单。
 *
 * <p>Worker 不自己实现数据库锁：它先通过 Store 领取短租约，再使用记录中的原始请求和
 * Idempotency-Key 调用售后平台。这样多个 Agent 实例同时扫描时也只有一个进入远端调用。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.handoff.recovery.enabled", havingValue = "true")
public class PendingHandoffRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(PendingHandoffRecoveryWorker.class);

    private final PendingHandoffStore store;
    private final AfterSalesTicketClient client;
    private final PendingHandoffRecoveryProperties properties;
    private final LongSupplier clock;
    private final Supplier<String> ownerTokenSupplier;

    public PendingHandoffRecoveryWorker(PendingHandoffStore store,
                                        AfterSalesTicketClient client,
                                        PendingHandoffRecoveryProperties properties) {
        this(store, client, properties, System::currentTimeMillis, () -> UUID.randomUUID().toString());
    }

    /** 测试构造器允许固定时钟和 owner token，使租约、退避断言不依赖真实等待。 */
    PendingHandoffRecoveryWorker(PendingHandoffStore store,
                                 AfterSalesTicketClient client,
                                 PendingHandoffRecoveryProperties properties,
                                 LongSupplier clock,
                                 Supplier<String> ownerTokenSupplier) {
        this.store = store;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.ownerTokenSupplier = ownerTokenSupplier;
    }

    @Scheduled(fixedDelayString = "${smartcs.handoff.recovery.fixed-delay-ms:5000}")
    public void recoverDue() {
        long now = clock.getAsLong();
        List<PendingHandoffRecord> due = store.findDue(now, properties.getBatchSize());
        for (PendingHandoffRecord candidate : due) {
            try {
                recoverOne(candidate, now);
            } catch (RuntimeException failure) {
                // 单条坏记录不能中断整个批次。日志只写异常类型，不写用户、会话、请求或异常原文。
                log.warn("Pending handoff recovery failed: exceptionType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void recoverOne(PendingHandoffRecord candidate, long now) {
        String ownerToken = ownerTokenSupplier.get();
        Optional<PendingHandoffClaim> optionalClaim = store.tryClaim(
                candidate.request().idempotencyKey(),
                candidate.version(),
                ownerToken,
                now,
                properties.getLeaseMs()
        );
        if (optionalClaim.isEmpty()) {
            return;
        }

        PendingHandoffClaim claim = optionalClaim.get();
        try {
            AfterSalesTicketCreateResult result = client.createTicket(claim.record().request());
            store.confirm(
                    claim.record().request().idempotencyKey(),
                    result,
                    claim.record().version()
            );
            store.releaseClaim(claim);
        } catch (RuntimeException failure) {
            handleFailure(claim, failure, now);
        }
    }

    private void handleFailure(PendingHandoffClaim claim, RuntimeException failure, long now) {
        // confirm() 可能已经写入追加式成功结果，只是在后续主表更新时抛错；先重新读取，避免把成功降级。
        Optional<PendingHandoffRecord> current = store.findByIdempotencyKey(
                claim.record().request().idempotencyKey()
        );
        if (current.isPresent() && current.get().status() == PendingHandoffStatus.CONFIRMED) {
            store.releaseClaim(claim);
            return;
        }

        int nextAttempt = claim.record().attemptCount() + 1;
        if (nextAttempt >= properties.getMaxAttempts()) {
            store.markNeedsManualReview(claim, failure.getClass().getSimpleName());
            return;
        }
        store.scheduleRetry(
                claim,
                failure.getClass().getSimpleName(),
                safeAdd(now, retryDelay(nextAttempt))
        );
    }

    private long retryDelay(int attempt) {
        long delay = properties.getRetryDelayMs();
        for (int i = 1; i < attempt && delay < properties.getMaxRetryDelayMs(); i++) {
            delay = Math.min(properties.getMaxRetryDelayMs(), safeMultiplyByTwo(delay));
        }
        return delay;
    }

    private long safeMultiplyByTwo(long value) {
        return value > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : value * 2;
    }

    private long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
