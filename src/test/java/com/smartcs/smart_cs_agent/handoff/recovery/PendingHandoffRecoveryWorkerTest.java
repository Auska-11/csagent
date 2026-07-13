package com.smartcs.smart_cs_agent.handoff.recovery;

import com.smartcs.smart_cs_agent.handoff.outbox.InMemoryPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketClient;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingHandoffRecoveryWorkerTest {

    @Test
    void shouldClaimDueRecordAndPersistConfirmedResult() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffRecord prepared = store.prepare(command("idem-worker-success")).record();
        AtomicLong clock = new AtomicLong(prepared.nextAttemptAt());
        AtomicInteger remoteCalls = new AtomicInteger();
        AfterSalesTicketClient client = (tenantId, request) -> {
            assertEquals("tenant-a", tenantId);
            remoteCalls.incrementAndGet();
            return new AfterSalesTicketCreateResult("AS-RECOVERED", "PENDING", "2026-07-13T00:00:00Z");
        };
        PendingHandoffRecoveryWorker worker = worker(store, client, clock, properties(3));

        worker.recoverDue();
        PendingHandoffRecord confirmed = store
                .findByKey(prepared.key())
                .orElseThrow();

        assertEquals(1, remoteCalls.get());
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals("AS-RECOVERED", confirmed.confirmedResult().ticketId());
    }

    @Test
    void shouldBackoffThenMoveToManualReviewAtMaximumAttempts() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffRecord prepared = store.prepare(command("idem-worker-failure")).record();
        AtomicLong clock = new AtomicLong(prepared.nextAttemptAt());
        AtomicInteger remoteCalls = new AtomicInteger();
        AfterSalesTicketClient client = (tenantId, request) -> {
            assertEquals("tenant-a", tenantId);
            remoteCalls.incrementAndGet();
            throw new IllegalStateException("internal endpoint must not be logged");
        };
        PendingHandoffRecoveryProperties properties = properties(2);
        PendingHandoffRecoveryWorker worker = worker(store, client, clock, properties);

        worker.recoverDue();
        PendingHandoffRecord retry = store
                .findByKey(prepared.key())
                .orElseThrow();
        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retry.status());
        assertEquals(clock.get() + properties.getRetryDelayMs(), retry.nextAttemptAt());
        assertEquals(1, retry.attemptCount());

        worker.recoverDue();
        assertEquals(1, remoteCalls.get(), "退避到期前不应重新调用远端");

        clock.set(retry.nextAttemptAt());
        worker.recoverDue();
        PendingHandoffRecord manual = store
                .findByKey(prepared.key())
                .orElseThrow();
        assertEquals(2, remoteCalls.get());
        assertEquals(PendingHandoffStatus.NEEDS_MANUAL_REVIEW, manual.status());
        assertEquals(2, manual.attemptCount());
        assertEquals("IllegalStateException", manual.failureType());
    }

    @Test
    void shouldRejectNonPositiveRecoverySettings() {
        PendingHandoffRecoveryProperties properties = new PendingHandoffRecoveryProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setPreparedGraceMs(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setLeaseMs(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setRetryDelayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxAttempts(0));
    }

    private PendingHandoffRecoveryWorker worker(InMemoryPendingHandoffStore store,
                                                 AfterSalesTicketClient client,
                                                 AtomicLong clock,
                                                 PendingHandoffRecoveryProperties properties) {
        AtomicInteger ownerSequence = new AtomicInteger();
        return new PendingHandoffRecoveryWorker(
                store,
                client,
                properties,
                clock::get,
                () -> "worker-" + ownerSequence.incrementAndGet()
        );
    }

    private PendingHandoffRecoveryProperties properties(int maxAttempts) {
        PendingHandoffRecoveryProperties properties = new PendingHandoffRecoveryProperties();
        properties.setBatchSize(10);
        properties.setLeaseMs(1_000);
        properties.setRetryDelayMs(2_000);
        properties.setMaxRetryDelayMs(10_000);
        properties.setMaxAttempts(maxAttempts);
        return properties;
    }

    private PendingHandoffCommand command(String key) {
        return new PendingHandoffCommand(
                "tenant-a",
                "USER-001",
                "conv-worker",
                "COMPLAINT",
                "后台恢复测试",
                new AfterSalesTicketCreateRequest(
                        "Agent 转人工：投诉",
                        "需要后台恢复",
                        "USER-001",
                        "general",
                        "complaint",
                        "URGENT",
                        key
                )
        );
    }
}
