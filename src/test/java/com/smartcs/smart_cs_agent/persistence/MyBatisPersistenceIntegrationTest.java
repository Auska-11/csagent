package com.smartcs.smart_cs_agent.persistence;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.core.CapabilityResultCodec;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyKeyConflictException;
import com.smartcs.smart_cs_agent.capability.idempotency.jdbc.JdbcCapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.handoff.outbox.JdbcPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffClaim;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffConflictException;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffPreparation;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionContext;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.CapabilityIdempotencyMapper;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.PendingHandoffMapper;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.jdbc.JdbcSessionStore;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 默认 CI 使用 H2 MySQL 模式执行真实 Mapper、连接池和 Spring 事务，不再模拟 PreparedStatement
 * 或 ResultSet。真实 MySQL 的驱动差异由 RealMySqlPersistenceIT 单独验收。
 */
@SpringBootTest(properties = {
        "smartcs.persistence.mode=jdbc",
        "smartcs.persistence.jdbc.driver-class-name=org.h2.Driver",
        "smartcs.persistence.jdbc.url=jdbc:h2:mem:smartcs_mybatis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "smartcs.persistence.jdbc.username=sa",
        "smartcs.persistence.jdbc.password=",
        "smartcs.persistence.jdbc.connect-timeout-ms=1000",
        "smartcs.persistence.jdbc.socket-timeout-ms=2000",
        "smartcs.persistence.jdbc.query-timeout-seconds=1",
        "smartcs.persistence.jdbc.pool-max-size=4",
        "smartcs.handoff.recovery.prepared-grace-ms=1"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MyBatisPersistenceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private JdbcSessionStore sessionStore;

    @Autowired
    private JdbcCapabilityIdempotencyStore idempotencyStore;

    @Autowired
    private JdbcPendingHandoffStore handoffStore;

    @Autowired
    private CapabilityIdempotencyMapper idempotencyMapper;

    @Autowired
    private MyBatisMapperExecutor mapperExecutor;

    @Autowired
    private CapabilityResultCodec resultCodec;

    @SpyBean
    private PendingHandoffMapper handoffMapper;

    @Test
    @Order(1)
    void shouldUseHikariAndPreserveSessionCas() {
        assertInstanceOf(HikariDataSource.class, dataSource);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertEquals(1_000L, hikari.getConnectionTimeout());
        assertEquals(4, hikari.getMaximumPoolSize());
        assertEquals(1_000, hikari.getDataSourceProperties().get("connectTimeout"));
        assertEquals(2_000, hikari.getDataSourceProperties().get("socketTimeout"));
        assertEquals(
                1,
                sqlSessionFactory.getConfiguration().getDefaultStatementTimeout()
        );
        String suffix = UUID.randomUUID().toString();
        ConversationKey key = ConversationKey.defaultTenant("USER-CAS", "conv-" + suffix);
        ConversationState state = new ConversationState("first", "USER-CAS");

        SessionRecord created = sessionStore.save(key, 0, state.toPersistenceSnapshot());
        state.startNewTurn("second");
        SessionRecord updated = sessionStore.save(
                key,
                created.version(),
                state.toPersistenceSnapshot()
        );

        SessionVersionConflictException stale = assertThrows(
                SessionVersionConflictException.class,
                () -> sessionStore.save(key, created.version(), state.toPersistenceSnapshot())
        );
        assertEquals(updated.version(), stale.getActualVersion());
        assertThrows(
                SessionVersionConflictException.class,
                () -> sessionStore.save(key, 0, state.toPersistenceSnapshot())
        );
        assertTrue(sessionStore.delete(key, updated.version()));
    }

    @Test
    @Order(2)
    void shouldKeepIdempotencyOwnershipReplayFingerprintAndExpiredLeaseTakeover() {
        String suffix = UUID.randomUUID().toString();
        String key = "idem-" + suffix;
        AtomicInteger providerCalls = new AtomicInteger();

        CapabilityResult<?> first = idempotencyStore.execute(
                key,
                "fingerprint-a",
                () -> {
                    providerCalls.incrementAndGet();
                    return CapabilityResult.success(
                            "capability.test",
                            "provider.test",
                            "ok",
                            Map.of("value", 1)
                    );
                }
        );
        CapabilityResult<?> replay = idempotencyStore.execute(
                key,
                "fingerprint-a",
                () -> {
                    providerCalls.incrementAndGet();
                    return CapabilityResult.technicalFailure(
                            "capability.test",
                            "provider.test",
                            "SHOULD_NOT_RUN",
                            "unexpected"
                    );
                }
        );

        assertTrue(first.isSuccess());
        assertTrue(replay.isReplayed());
        assertEquals(1, providerCalls.get());
        assertThrows(
                IdempotencyKeyConflictException.class,
                () -> idempotencyStore.execute(
                        key,
                        "fingerprint-b",
                        () -> first
                )
        );

        String expiredKey = "expired-" + suffix;
        long now = System.currentTimeMillis();
        idempotencyMapper.insertClaim(
                sha256(expiredKey),
                sha256("same-request"),
                "IN_PROGRESS",
                "dead-owner",
                now - 1,
                now - 10
        );
        CapabilityResult<?> takeover = idempotencyStore.execute(
                expiredKey,
                "same-request",
                () -> CapabilityResult.success(
                        "capability.takeover",
                        "provider.test",
                        "taken over",
                        null
                )
        );
        assertTrue(takeover.isSuccess());
        assertEquals("COMPLETED", idempotencyMapper.find(sha256(expiredKey)).status());
    }

    @Test
    @Order(3)
    void shouldRenewLeaseWhileSlowProviderRunsAndKeepSingleExecution() throws Exception {
        JdbcCapabilityIdempotencyStore shortLeaseStore =
                new JdbcCapabilityIdempotencyStore(
                        idempotencyMapper,
                        mapperExecutor,
                        resultCodec,
                        90,
                        2_000,
                        5
                );
        String key = "renewal-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResult<?>> owner = executor.submit(() -> shortLeaseStore.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return CapabilityResult.success(
                                "capability.renewal",
                                "provider.test",
                                "ok",
                                null
                        );
                    }
            ));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.sleep(180);
            Future<CapabilityResult<?>> waiter = executor.submit(() -> shortLeaseStore.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        calls.incrementAndGet();
                        return CapabilityResult.technicalFailure(
                                "capability.renewal",
                                "provider.test",
                                "DUPLICATE",
                                "must not execute"
                        );
                    }
            ));
            Thread.sleep(50);
            release.countDown();

            assertTrue(owner.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(waiter.get(2, TimeUnit.SECONDS).isReplayed());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Order(4)
    void shouldPersistOutboxBeforeProcessingAndKeepLeaseCasAndResultReplay() {
        PendingHandoffCommand command = command("handoff-" + UUID.randomUUID());
        PendingHandoffPreparation prepared = handoffStore.prepare(command);
        assertTrue(prepared.created());
        assertEquals(PendingHandoffStatus.PREPARED, prepared.record().status());
        assertNotNull(handoffStore.findByKey(command.key()).orElseThrow());

        PendingHandoffPreparation duplicate = handoffStore.prepare(command);
        assertEquals(false, duplicate.created());
        long dueAt = System.currentTimeMillis() + 5;
        PendingHandoffRecord unknown = handoffStore.markResultUnknown(
                command.key(),
                "SocketTimeoutException",
                prepared.record().version()
        );
        PendingHandoffClaim claim = handoffStore.tryClaim(
                command.key(),
                unknown.version(),
                "worker-a",
                dueAt,
                10_000
        ).orElseThrow();
        PendingHandoffRecord retry = handoffStore.scheduleRetry(
                claim,
                "REMOTE_UNAVAILABLE",
                dueAt + 100
        );
        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retry.status());
        assertTrue(handoffStore.tryClaim(
                command.key(),
                retry.version() - 1,
                "stale-worker",
                dueAt + 200,
                10_000
        ).isEmpty());
    }

    @Test
    @Order(5)
    void shouldEnforceHandoffFingerprintTenantIsolationAndConfirmedReplay() {
        String sharedKey = "shared-" + UUID.randomUUID();
        PendingHandoffCommand tenantA = command("tenant-a", sharedKey, "summary-a");
        PendingHandoffCommand tenantB = command("tenant-b", sharedKey, "summary-b");
        PendingHandoffRecord first = handoffStore.prepare(tenantA).record();
        PendingHandoffRecord isolated = handoffStore.prepare(tenantB).record();
        assertEquals("tenant-a", first.tenantId());
        assertEquals("tenant-b", isolated.tenantId());

        PendingHandoffCommand conflicting = command("tenant-a", sharedKey, "different");
        assertThrows(
                PendingHandoffConflictException.class,
                () -> handoffStore.prepare(conflicting)
        );

        AfterSalesTicketCreateResult result = new AfterSalesTicketCreateResult(
                "TICKET-" + UUID.randomUUID(),
                "OPEN",
                "2026-07-18T00:00:00Z"
        );
        PendingHandoffRecord confirmed = handoffStore.confirm(
                tenantA.key(),
                result,
                first.version()
        );
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals(result, confirmed.confirmedResult());
        assertTrue(handoffStore.findReviewPage(
                "tenant-a",
                PendingHandoffStatus.CONFIRMED,
                null,
                10
        ).records().stream().anyMatch(row -> row.outboxId().equals(confirmed.outboxId())));
        assertEquals(
                result,
                handoffStore.confirm(tenantA.key(), result, confirmed.version())
                        .confirmedResult()
        );
        assertThrows(
                PendingHandoffConflictException.class,
                () -> handoffStore.confirm(
                        tenantA.key(),
                        new AfterSalesTicketCreateResult("OTHER", "OPEN", "later"),
                        confirmed.version()
                )
        );
    }

    @Test
    @Order(6)
    void shouldCommitManualStateAndAppendOnlyAuditTogether() {
        PendingHandoffRecord review = moveToManualReview(
                command("audit-commit-" + UUID.randomUUID())
        );
        PendingHandoffManualDecisionContext context = retryContext(review);

        var decision = handoffStore.retry(context);

        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, decision.record().status());
        assertTrue(handoffStore.findReviewPage(
                review.tenantId(),
                PendingHandoffStatus.RETRY_SCHEDULED,
                null,
                10
        ).records().stream().anyMatch(row -> row.outboxId().equals(review.outboxId())));
        var auditPage = handoffStore.findAuditPage(
                review.tenantId(),
                review.outboxId(),
                null,
                10
        );
        assertEquals(1, auditPage.events().size());
        assertEquals(review.version() + 1, auditPage.events().get(0).toVersion());
    }

    @Test
    @Order(99)
    void shouldRollbackManualStateWhenAppendOnlyAuditInsertFails() {
        PendingHandoffCommand command = command("audit-rollback-" + UUID.randomUUID());
        PendingHandoffRecord review = moveToManualReview(command);
        PendingHandoffManualDecisionContext context = retryContext(review);

        doThrow(new IllegalStateException("injected audit failure"))
                .when(handoffMapper).insertAudit(any());
        assertThrows(IllegalStateException.class, () -> handoffStore.retry(context));

        PendingHandoffRecord unchanged = handoffStore.findByKey(command.key()).orElseThrow();
        assertEquals(PendingHandoffStatus.NEEDS_MANUAL_REVIEW, unchanged.status());
        assertEquals(review.version(), unchanged.version());
        assertTrue(handoffStore.findAuditPage(
                review.tenantId(),
                review.outboxId(),
                null,
                10
        ).events().isEmpty());
    }

    private PendingHandoffRecord moveToManualReview(PendingHandoffCommand command) {
        PendingHandoffRecord prepared = handoffStore.prepare(command).record();
        PendingHandoffRecord unknown = handoffStore.markResultUnknown(
                command.key(),
                "UNKNOWN_RESULT",
                prepared.version()
        );
        long now = System.currentTimeMillis() + 5;
        PendingHandoffClaim claim = handoffStore.tryClaim(
                command.key(),
                unknown.version(),
                "review-worker",
                now,
                10_000
        ).orElseThrow();
        return handoffStore.markNeedsManualReview(claim, "MAX_ATTEMPTS_REACHED");
    }

    private PendingHandoffManualDecisionContext retryContext(PendingHandoffRecord review) {
        return new PendingHandoffManualDecisionContext(
                review.tenantId(),
                review.outboxId(),
                review.version(),
                "admin-1",
                PendingHandoffReviewAuthority.ADMIN,
                "RETRY_AFTER_REVIEW",
                "verified",
                PendingHandoffEvidenceSource.NONE
        );
    }

    private PendingHandoffCommand command(String idempotencyKey) {
        return command("tenant-test", idempotencyKey, "need a human");
    }

    private PendingHandoffCommand command(String tenantId,
                                          String idempotencyKey,
                                          String summary) {
        return new PendingHandoffCommand(
                tenantId,
                "USER-001",
                "conversation-test",
                "USER_REQUEST",
                summary,
                new AfterSalesTicketCreateRequest(
                        "help",
                        "description",
                        "USER-001",
                        "electronics",
                        "support",
                        "NORMAL",
                        idempotencyKey
                )
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
