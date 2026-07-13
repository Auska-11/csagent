package com.smartcs.smart_cs_agent.capability.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.core.CapabilityResultCodec;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyKeyConflictException;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyWaitDeadlineExceededException;
import com.smartcs.smart_cs_agent.capability.idempotency.jdbc.JdbcCapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** JDBC 幂等 Store 的跨实例抢占、结果回放、租约和冲突契约测试。 */
class JdbcCapabilityIdempotencyStoreTest {

    @Test
    void shouldReplayCompletedResultAfterStoreRecreation() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore firstStore = store(database, codec);
        AtomicInteger invocations = new AtomicInteger();

        CapabilityResult<?> first = firstStore.execute("tenant:return.apply:key-1", "fingerprint-a", () -> {
            invocations.incrementAndGet();
            return CapabilityResult.success(
                    "return.apply",
                    "test-provider",
                    "submitted",
                    Map.of("return_id", "RET-900", "submitted", true)
            );
        });

        // 新建 Store 模拟应用重启。数据库记录仍在，因此 operation 不能再次执行。
        JdbcCapabilityIdempotencyStore restartedStore = store(database, codec);
        CapabilityResult<?> replay = restartedStore.execute(
                "tenant:return.apply:key-1",
                "fingerprint-a",
                () -> {
                    invocations.incrementAndGet();
                    throw new IllegalStateException("replayed operation must not execute");
                }
        );

        assertTrue(first.isSuccess());
        assertFalse(first.isReplayed());
        assertTrue(replay.isSuccess());
        assertTrue(replay.isReplayed());
        assertEquals(1, invocations.get());
        Map<?, ?> replayData = assertInstanceOf(Map.class, replay.getData());
        assertEquals("RET-900", replayData.get("return_id"));
        assertTrue(database.createTableSql.contains("idx_cs_capability_idempotency_updated_at"));
    }

    @Test
    void shouldCoalesceConcurrentCallsAcrossStoreInstancesAndRejectFingerprintConflict() throws Exception {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore firstStore = store(database, codec);
        JdbcCapabilityIdempotencyStore secondStore = store(database, codec);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CapabilityResult<?>> first = executor.submit(() -> firstStore.execute(
                    "tenant:handoff.request:key-2",
                    "fingerprint-b",
                    () -> {
                        invocations.incrementAndGet();
                        providerEntered.countDown();
                        await(releaseProvider);
                        return CapabilityResult.success(
                                "handoff.request",
                                "test-provider",
                                "created",
                                Map.of("ticket_id", "HD-900")
                        );
                    }
            ));
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS));

            Future<CapabilityResult<?>> second = executor.submit(() -> secondStore.execute(
                    "tenant:handoff.request:key-2",
                    "fingerprint-b",
                    () -> {
                        invocations.incrementAndGet();
                        return CapabilityResult.success(
                                "handoff.request",
                                "test-provider",
                                "duplicate",
                                Map.of()
                        );
                    }
            ));

            // 确认第二个 Store 已经撞到数据库唯一键，再放行第一个 Provider。
            assertTrue(database.duplicateClaimObserved.await(2, TimeUnit.SECONDS));
            releaseProvider.countDown();

            CapabilityResult<?> firstResult = first.get(2, TimeUnit.SECONDS);
            CapabilityResult<?> secondResult = second.get(2, TimeUnit.SECONDS);
            assertEquals(1, invocations.get());
            assertTrue(firstResult.isSuccess());
            assertTrue(secondResult.isSuccess());
            assertTrue(firstResult.isReplayed() ^ secondResult.isReplayed());
        } finally {
            executor.shutdownNow();
        }

        assertThrows(
                IdempotencyKeyConflictException.class,
                () -> secondStore.execute(
                        "tenant:handoff.request:key-2",
                        "different-fingerprint",
                        () -> CapabilityResult.success(
                                "handoff.request", "test-provider", "wrong", Map.of()
                        )
                )
        );
    }

    @Test
    void shouldStopWaitingForExistingOwnerAtRequestDeadline() throws Exception {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore firstStore = store(database, codec);
        JdbcCapabilityIdempotencyStore secondStore = store(database, codec);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<CapabilityResult<?>> owner = executor.submit(() -> firstStore.execute(
                    "tenant:return.apply:deadline-wait",
                    "deadline-fingerprint",
                    () -> {
                        invocations.incrementAndGet();
                        providerEntered.countDown();
                        await(releaseProvider);
                        return CapabilityResult.success(
                                "return.apply", "test-provider", "submitted", Map.of()
                        );
                    }
            ));
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS));

            assertThrows(
                    IdempotencyWaitDeadlineExceededException.class,
                    () -> secondStore.execute(
                            "tenant:return.apply:deadline-wait",
                            "deadline-fingerprint",
                            () -> {
                                invocations.incrementAndGet();
                                return CapabilityResult.success(
                                        "return.apply", "test-provider", "duplicate", Map.of()
                                );
                            },
                            Instant.now().plusMillis(80)
                    )
            );
            assertEquals(1, invocations.get());

            releaseProvider.countDown();
            assertTrue(owner.get(2, TimeUnit.SECONDS).isSuccess());
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReplayTechnicalFailureWithoutRetryingUnknownOutcome() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcCapabilityIdempotencyStore store = store(
                database,
                new CapabilityResultCodec(new ObjectMapper())
        );
        AtomicInteger invocations = new AtomicInteger();

        CapabilityResult<?> failed = store.execute("tenant:return.apply:key-3", "fingerprint-c", () -> {
            invocations.incrementAndGet();
            return CapabilityResult.technicalFailure(
                    "return.apply", "test-provider", "DATABASE_TIMEOUT", "timeout"
            );
        });
        CapabilityResult<?> replay = store.execute("tenant:return.apply:key-3", "fingerprint-c", () -> {
            invocations.incrementAndGet();
            return CapabilityResult.success(
                    "return.apply", "test-provider", "submitted", Map.of("return_id", "RET-901")
            );
        });

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, failed.getOutcome());
        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, replay.getOutcome());
        assertTrue(replay.isReplayed());
        assertEquals(1, invocations.get());
    }

    @Test
    void shouldTakeOverExpiredLeaseAfterPreviousOwnerCrashed() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcCapabilityIdempotencyStore store = store(
                database,
                new CapabilityResultCodec(new ObjectMapper())
        );
        database.seedExpiredClaim("tenant:return.apply:key-4", "fingerprint-d");
        AtomicInteger invocations = new AtomicInteger();

        CapabilityResult<?> result = store.execute("tenant:return.apply:key-4", "fingerprint-d", () -> {
            invocations.incrementAndGet();
            return CapabilityResult.businessRejected(
                    "return.apply",
                    "test-provider",
                    "DUPLICATE_RETURN_APPLICATION",
                    "already submitted",
                    Map.of("return_id", "RET-902")
            );
        });

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals(1, invocations.get());
        assertTrue(database.takeoverCount.get() > 0);
    }

    @Test
    void shouldRenewLeaseWhileSlowProviderIsStillRunning() throws Exception {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore firstStore = store(database, codec, 60, 1_000, 5);
        JdbcCapabilityIdempotencyStore secondStore = store(database, codec, 60, 1_000, 5);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CapabilityResult<?>> first = executor.submit(() -> firstStore.execute(
                    "tenant:return.apply:slow-key",
                    "slow-fingerprint",
                    () -> {
                        invocations.incrementAndGet();
                        providerEntered.countDown();
                        await(releaseProvider);
                        return CapabilityResult.success(
                                "return.apply",
                                "test-provider",
                                "submitted",
                                Map.of("return_id", "RET-SLOW")
                        );
                    }
            ));
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS));

            Future<CapabilityResult<?>> second = executor.submit(() -> secondStore.execute(
                    "tenant:return.apply:slow-key",
                    "slow-fingerprint",
                    () -> {
                        invocations.incrementAndGet();
                        return CapabilityResult.success(
                                "return.apply", "test-provider", "duplicate", Map.of()
                        );
                    }
            ));
            assertTrue(database.duplicateClaimObserved.await(2, TimeUnit.SECONDS));

            // 等待至少三次续租，已经跨过最初 60ms 租约，第二个 Store 仍不得接管。
            assertTrue(database.leaseRenewed.await(2, TimeUnit.SECONDS));
            releaseProvider.countDown();

            assertTrue(first.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(second.get(2, TimeUnit.SECONDS).isReplayed());
            assertEquals(1, invocations.get());
            assertEquals(0, database.takeoverCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRetryClaimWhenPreviousOwnerReleasesImmediatelyAfterDuplicateInsert() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcCapabilityIdempotencyStore store = store(
                database,
                new CapabilityResultCodec(new ObjectMapper())
        );
        database.seedActiveClaim("tenant:return.apply:release-race", "release-race-fingerprint");
        database.removeClaimOnNextDuplicate.set(true);
        AtomicInteger invocations = new AtomicInteger();

        CapabilityResult<?> result = store.execute(
                "tenant:return.apply:release-race",
                "release-race-fingerprint",
                () -> {
                    invocations.incrementAndGet();
                    return CapabilityResult.success(
                            "return.apply", "test-provider", "submitted", Map.of()
                    );
                }
        );

        assertTrue(result.isSuccess());
        assertEquals(1, invocations.get());
    }

    @Test
    void shouldKeepRenewingLeaseUntilCompletedResultIsPersisted() throws Exception {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore firstStore = store(database, codec, 60, 1_000, 5);
        JdbcCapabilityIdempotencyStore secondStore = store(database, codec, 60, 1_000, 5);
        database.blockNextCompletion.set(true);
        AtomicInteger invocations = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CapabilityResult<?>> first = executor.submit(() -> firstStore.execute(
                    "tenant:return.apply:slow-completion",
                    "slow-completion-fingerprint",
                    () -> {
                        invocations.incrementAndGet();
                        return CapabilityResult.success(
                                "return.apply",
                                "test-provider",
                                "submitted",
                                Map.of("return_id", "RET-COMPLETION")
                        );
                    }
            ));
            assertTrue(database.completionUpdateEntered.await(2, TimeUnit.SECONDS));

            Future<CapabilityResult<?>> second = executor.submit(() -> secondStore.execute(
                    "tenant:return.apply:slow-completion",
                    "slow-completion-fingerprint",
                    () -> {
                        invocations.incrementAndGet();
                        return CapabilityResult.success(
                                "return.apply", "test-provider", "duplicate", Map.of()
                        );
                    }
            ));
            assertTrue(database.duplicateClaimObserved.await(2, TimeUnit.SECONDS));

            // Provider 已返回，但完成 UPDATE 仍被阻塞。续租必须继续覆盖这个收尾窗口。
            assertTrue(database.leaseRenewed.await(2, TimeUnit.SECONDS));
            database.releaseCompletionUpdate.countDown();

            assertTrue(first.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(second.get(2, TimeUnit.SECONDS).isReplayed());
            assertEquals(1, invocations.get());
            assertEquals(0, database.takeoverCount.get());
        } finally {
            database.releaseCompletionUpdate.countDown();
            executor.shutdownNow();
        }
    }

    private JdbcCapabilityIdempotencyStore store(RecordingJdbcDatabaseClient database,
                                                 CapabilityResultCodec codec) {
        return new JdbcCapabilityIdempotencyStore(database, codec, 10_000, 2_000, 5);
    }

    private JdbcCapabilityIdempotencyStore store(RecordingJdbcDatabaseClient database,
                                                 CapabilityResultCodec codec,
                                                 long leaseMs,
                                                 long waitTimeoutMs,
                                                 long pollIntervalMs) {
        return new JdbcCapabilityIdempotencyStore(
                database,
                codec,
                leaseMs,
                waitTimeoutMs,
                pollIntervalMs
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }

    /**
     * 不依赖真实 MySQL 的数据库替身。它按 SQL 参数模拟唯一主键和条件 UPDATE，重点验证 Store
     * 发出的并发条件是否完整；真实 MySQL/Testcontainers 仍会作为后续独立批次补充。
     */
    private static final class RecordingJdbcDatabaseClient extends JdbcDatabaseClient {

        private final Map<String, Row> rows = new ConcurrentHashMap<>();
        private final CountDownLatch duplicateClaimObserved = new CountDownLatch(1);
        private final CountDownLatch leaseRenewed = new CountDownLatch(3);
        private final CountDownLatch completionUpdateEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCompletionUpdate = new CountDownLatch(1);
        private final AtomicInteger takeoverCount = new AtomicInteger();
        private final AtomicBoolean removeClaimOnNextDuplicate = new AtomicBoolean();
        private final AtomicBoolean blockNextCompletion = new AtomicBoolean();
        private String createTableSql = "";

        private RecordingJdbcDatabaseClient() {
            super("memory", "", "", "", "");
        }

        @Override
        public int update(String sql, Object... args) {
            String normalized = sql.trim();
            if (normalized.startsWith("CREATE TABLE")) {
                createTableSql = sql;
                return 0;
            }
            if (normalized.startsWith("INSERT INTO cs_capability_idempotency")) {
                String keyHash = args[0].toString();
                Row row = new Row(
                        args[1].toString(),
                        args[2].toString(),
                        args[3].toString(),
                        number(args[4]).longValue(),
                        null,
                        0
                );
                Row existing = rows.putIfAbsent(keyHash, row);
                if (existing != null) {
                    duplicateClaimObserved.countDown();
                    if (removeClaimOnNextDuplicate.compareAndSet(true, false)) {
                        rows.remove(keyHash, existing);
                    }
                    throw new JdbcDuplicateKeyException(
                            new SQLException("duplicate primary key", "23000", 1062)
                    );
                }
                return 1;
            }
            if (normalized.startsWith("UPDATE cs_capability_idempotency")
                    && normalized.contains("SET owner_token")) {
                return takeOver(args);
            }
            if (normalized.startsWith("UPDATE cs_capability_idempotency")
                    && normalized.contains("SET lease_until")) {
                return renew(args);
            }
            if (normalized.startsWith("UPDATE cs_capability_idempotency")
                    && normalized.contains("SET status")) {
                return complete(args);
            }
            if (normalized.startsWith("DELETE FROM cs_capability_idempotency")) {
                return release(args);
            }
            throw new IllegalArgumentException("Unexpected SQL: " + sql);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            Row row = rows.get(args[0].toString());
            if (row == null) {
                return Optional.empty();
            }

            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getString("request_fingerprint_hash"))
                        .thenReturn(row.requestFingerprintHash);
                when(resultSet.getString("status")).thenReturn(row.status);
                when(resultSet.getString("owner_token")).thenReturn(row.ownerToken);
                when(resultSet.getLong("lease_until")).thenReturn(row.leaseUntil);
                when(resultSet.getString("result_json")).thenReturn(row.resultJson);
                when(resultSet.getInt("result_schema_version")).thenReturn(row.resultSchemaVersion);
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute test RowMapper", e);
            }
        }

        private int takeOver(Object[] args) {
            AtomicInteger changed = new AtomicInteger();
            String keyHash = args[3].toString();
            rows.computeIfPresent(keyHash, (ignored, existing) -> {
                if (!existing.requestFingerprintHash.equals(args[4].toString())
                        || !existing.status.equals(args[5].toString())
                        || !existing.ownerToken.equals(args[6].toString())
                        || existing.leaseUntil != number(args[7]).longValue()) {
                    return existing;
                }
                changed.set(1);
                takeoverCount.incrementAndGet();
                return new Row(
                        existing.requestFingerprintHash,
                        existing.status,
                        args[0].toString(),
                        number(args[1]).longValue(),
                        existing.resultJson,
                        existing.resultSchemaVersion
                );
            });
            return changed.get();
        }

        private int complete(Object[] args) {
            if (blockNextCompletion.compareAndSet(true, false)) {
                completionUpdateEntered.countDown();
                await(releaseCompletionUpdate);
            }
            AtomicInteger changed = new AtomicInteger();
            String keyHash = args[4].toString();
            rows.computeIfPresent(keyHash, (ignored, existing) -> {
                if (!existing.requestFingerprintHash.equals(args[5].toString())
                        || !existing.status.equals(args[6].toString())
                        || !existing.ownerToken.equals(args[7].toString())) {
                    return existing;
                }
                changed.set(1);
                return new Row(
                        existing.requestFingerprintHash,
                        args[0].toString(),
                        existing.ownerToken,
                        0,
                        args[1].toString(),
                        number(args[2]).intValue()
                );
            });
            return changed.get();
        }

        private int renew(Object[] args) {
            AtomicInteger changed = new AtomicInteger();
            String keyHash = args[2].toString();
            rows.computeIfPresent(keyHash, (ignored, existing) -> {
                if (!existing.requestFingerprintHash.equals(args[3].toString())
                        || !existing.status.equals(args[4].toString())
                        || !existing.ownerToken.equals(args[5].toString())) {
                    return existing;
                }
                changed.set(1);
                leaseRenewed.countDown();
                return new Row(
                        existing.requestFingerprintHash,
                        existing.status,
                        existing.ownerToken,
                        number(args[0]).longValue(),
                        existing.resultJson,
                        existing.resultSchemaVersion
                );
            });
            return changed.get();
        }

        private int release(Object[] args) {
            AtomicInteger changed = new AtomicInteger();
            String keyHash = args[0].toString();
            rows.computeIfPresent(keyHash, (ignored, existing) -> {
                if (!existing.status.equals(args[1].toString())
                        || !existing.ownerToken.equals(args[2].toString())) {
                    return existing;
                }
                changed.set(1);
                return null;
            });
            return changed.get();
        }

        private void seedExpiredClaim(String storageKey, String fingerprint) {
            rows.put(
                    sha256(storageKey),
                    new Row(
                            sha256(fingerprint),
                            "IN_PROGRESS",
                            "crashed-owner",
                            System.currentTimeMillis() - 1,
                            null,
                            0
                    )
            );
        }

        private void seedActiveClaim(String storageKey, String fingerprint) {
            rows.put(
                    sha256(storageKey),
                    new Row(
                            sha256(fingerprint),
                            "IN_PROGRESS",
                            "owner-about-to-release",
                            System.currentTimeMillis() + 10_000,
                            null,
                            0
                    )
            );
        }

        private Number number(Object value) {
            return (Number) value;
        }

        private String sha256(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private record Row(
            String requestFingerprintHash,
            String status,
            String ownerToken,
            long leaseUntil,
            String resultJson,
            int resultSchemaVersion
    ) {
    }
}
