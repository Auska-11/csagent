package com.smartcs.smart_cs_agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.core.CapabilityResultCodec;
import com.smartcs.smart_cs_agent.capability.idempotency.jdbc.JdbcCapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.handoff.outbox.JdbcPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffClaim;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.core.ConversationStateCodec;
import com.smartcs.smart_cs_agent.session.jdbc.JdbcSessionStore;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 验收测试。
 *
 * <p>类名以 IT 结尾，因此默认 mvn test 不会执行；启动 Compose MySQL 后显式运行：</p>
 * <pre>mvn -Dtest=RealMySqlPersistenceIT test</pre>
 *
 * <p>它不使用数据库替身，直接验证 MySQL 唯一键、CAS、并发等待、租约接管和查询超时。</p>
 */
class RealMySqlPersistenceIT {

    @Test
    void shouldVerifySessionCasIdempotencyLeaseAndQueryTimeoutAgainstRealMysql() throws Exception {
        JdbcDatabaseClient database = databaseClient(1);
        String suffix = UUID.randomUUID().toString();

        verifySessionCas(database, suffix);
        verifyIdempotencyConcurrency(database, suffix);
        verifyOutboxLease(database, suffix);
        verifyQueryTimeout(database);
    }

    private void verifySessionCas(JdbcDatabaseClient database, String suffix) {
        ConversationStateCodec codec = new ConversationStateCodec(new ObjectMapper());
        JdbcSessionStore first = new JdbcSessionStore(database, codec);
        JdbcSessionStore second = new JdbcSessionStore(database, codec);
        ConversationKey key = ConversationKey.defaultTenant("USER-REAL", "real-session-" + suffix);
        ConversationState state = new ConversationState("真实 MySQL", "USER-REAL");

        SessionRecord created = first.save(key, 0, state.toPersistenceSnapshot());
        SessionRecord restored = second.load(key).orElseThrow();
        assertEquals(1, restored.version());
        assertEquals("真实 MySQL", restored.snapshot().message());

        state.startNewTurn("CAS 更新");
        SessionRecord updated = second.save(key, restored.version(), state.toPersistenceSnapshot());
        assertEquals(2, updated.version());
        assertThrows(
                SessionVersionConflictException.class,
                () -> first.save(key, created.version(), state.toPersistenceSnapshot())
        );
        assertTrue(second.delete(key, updated.version()));
    }

    private void verifyIdempotencyConcurrency(JdbcDatabaseClient database, String suffix) throws Exception {
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore first = new JdbcCapabilityIdempotencyStore(
                database, codec, 5_000, 5_000, 20
        );
        JdbcCapabilityIdempotencyStore second = new JdbcCapabilityIdempotencyStore(
                database, codec, 5_000, 5_000, 20
        );
        String key = "real-mysql-idempotency-" + suffix;
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResult<?>> owner = executor.submit(() -> first.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        providerCalls.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return CapabilityResult.success(
                                "handoff.request", "real-mysql", "ok", Map.of("ticket", "AS-REAL")
                        );
                    }
            ));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<CapabilityResult<?>> waiter = executor.submit(() -> second.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        providerCalls.incrementAndGet();
                        throw new IllegalStateException("second provider must not execute");
                    }
            ));
            release.countDown();

            assertTrue(owner.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(waiter.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, providerCalls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void verifyOutboxLease(JdbcDatabaseClient database, String suffix) {
        JdbcPendingHandoffStore first = new JdbcPendingHandoffStore(database, 1);
        JdbcPendingHandoffStore second = new JdbcPendingHandoffStore(database, 1);
        String key = "real-outbox-" + suffix;
        PendingHandoffRecord prepared = first.prepare(new PendingHandoffCommand(
                "USER-REAL",
                "real-conversation-" + suffix,
                "COMPLAINT",
                "真实数据库租约",
                new AfterSalesTicketCreateRequest(
                        "真实 MySQL 工单",
                        "验证 Outbox 租约",
                        "USER-REAL",
                        "general",
                        "complaint",
                        "HIGH",
                        key
                )
        )).record();
        long due = prepared.nextAttemptAt();

        PendingHandoffClaim owner = first.tryClaim(
                key, prepared.version(), "real-owner-1", due, 100
        ).orElseThrow();
        assertTrue(second.tryClaim(
                key, owner.record().version(), "real-owner-2", due + 50, 100
        ).isEmpty());
        PendingHandoffClaim takeover = second.tryClaim(
                key, owner.record().version(), "real-owner-2", due + 100, 100
        ).orElseThrow();
        PendingHandoffRecord manual = second.markNeedsManualReview(takeover, "RealMysqlVerified");
        assertEquals("NEEDS_MANUAL_REVIEW", manual.status().name());
    }

    private void verifyQueryTimeout(JdbcDatabaseClient database) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(
                IllegalStateException.class,
                () -> database.queryLong("SELECT SLEEP(2)")
        ));
    }

    private JdbcDatabaseClient databaseClient(int queryTimeoutSeconds) {
        String url = System.getProperty(
                "smartcs.real.mysql.url",
                "jdbc:mysql://127.0.0.1:3307/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        );
        String username = System.getProperty("smartcs.real.mysql.username", "smartcs");
        String password = System.getProperty("smartcs.real.mysql.password", "smartcs-local-password");
        return new JdbcDatabaseClient(
                "jdbc",
                "com.mysql.cj.jdbc.Driver",
                url,
                username,
                password,
                3_000,
                10_000,
                queryTimeoutSeconds
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }
}
