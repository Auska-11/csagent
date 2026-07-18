package com.smartcs.smart_cs_agent.persistence;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.jdbc.JdbcCapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.handoff.outbox.JdbcPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffPreparation;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.jdbc.JdbcSessionStore;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式运行的真实 MySQL 验收：
 * {@code mvn -Dtest=RealMySqlPersistenceIT test}。
 */
@SpringBootTest(properties = {
        "smartcs.persistence.mode=jdbc",
        "smartcs.persistence.jdbc.driver-class-name=${SMARTCS_JDBC_DRIVER:com.mysql.cj.jdbc.Driver}",
        "smartcs.persistence.jdbc.url=${SMARTCS_JDBC_URL:jdbc:mysql://localhost:3306/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}",
        "smartcs.persistence.jdbc.username=${SMARTCS_JDBC_USERNAME:root}",
        "smartcs.persistence.jdbc.password=${SMARTCS_JDBC_PASSWORD:}",
        "smartcs.persistence.jdbc.connect-timeout-ms=${SMARTCS_JDBC_CONNECT_TIMEOUT_MS:3000}",
        "smartcs.persistence.jdbc.socket-timeout-ms=${SMARTCS_JDBC_SOCKET_TIMEOUT_MS:10000}",
        "smartcs.persistence.jdbc.query-timeout-seconds=1",
        "smartcs.persistence.jdbc.pool-max-size=4",
        "smartcs.handoff.recovery.prepared-grace-ms=1"
})
class RealMySqlPersistenceIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcSessionStore sessionStore;

    @Autowired
    private JdbcCapabilityIdempotencyStore idempotencyStore;

    @Autowired
    private JdbcPendingHandoffStore handoffStore;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private MyBatisMapperExecutor mapperExecutor;

    @Test
    void shouldVerifyCasUniqueExecutionOutboxReplayAndQueryTimeout() throws Exception {
        assertTrue(dataSource instanceof HikariDataSource);
        String suffix = UUID.randomUUID().toString();
        verifySessionCas(suffix);
        verifyUniqueExecution(suffix);
        verifyOutboxResultReplay(suffix);
        verifyQueryTimeout();
    }

    private void verifySessionCas(String suffix) {
        ConversationKey key = ConversationKey.defaultTenant(
                "USER-REAL",
                "mysql-session-" + suffix
        );
        ConversationState state = new ConversationState("first", "USER-REAL");
        SessionRecord created = sessionStore.save(key, 0, state.toPersistenceSnapshot());
        state.startNewTurn("second");
        SessionRecord updated = sessionStore.save(
                key,
                created.version(),
                state.toPersistenceSnapshot()
        );
        assertThrows(
                SessionVersionConflictException.class,
                () -> sessionStore.save(key, created.version(), state.toPersistenceSnapshot())
        );
        assertTrue(sessionStore.delete(key, updated.version()));
    }

    private void verifyUniqueExecution(String suffix) throws Exception {
        String key = "mysql-idem-" + suffix;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResult<?>> owner = executor.submit(() -> idempotencyStore.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return CapabilityResult.success(
                                "capability.mysql",
                                "provider.mysql",
                                "ok",
                                null
                        );
                    }
            ));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<CapabilityResult<?>> waiter = executor.submit(() -> idempotencyStore.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        calls.incrementAndGet();
                        return CapabilityResult.technicalFailure(
                                "capability.mysql",
                                "provider.mysql",
                                "DUPLICATE",
                                "must not execute"
                        );
                    }
            ));
            release.countDown();
            assertTrue(owner.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(waiter.get(5, TimeUnit.SECONDS).isReplayed());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void verifyOutboxResultReplay(String suffix) {
        String key = "mysql-handoff-" + suffix;
        PendingHandoffCommand command = new PendingHandoffCommand(
                "tenant-real",
                "USER-REAL",
                "conversation-real",
                "USER_REQUEST",
                "real mysql",
                new AfterSalesTicketCreateRequest(
                        "help",
                        "description",
                        "USER-REAL",
                        "electronics",
                        "support",
                        "NORMAL",
                        key
                )
        );
        PendingHandoffPreparation prepared = handoffStore.prepare(command);
        AfterSalesTicketCreateResult result = new AfterSalesTicketCreateResult(
                "TICKET-" + suffix,
                "OPEN",
                "2026-07-18T00:00:00Z"
        );
        assertEquals(
                result,
                handoffStore.confirm(command.key(), result, prepared.record().version())
                        .confirmedResult()
        );
        assertEquals(
                result,
                handoffStore.findByKey(command.key()).orElseThrow().confirmedResult()
        );
    }

    private void verifyQueryTimeout() {
        if (!sqlSessionFactory.getConfiguration().hasMapper(SlowQueryMapper.class)) {
            sqlSessionFactory.getConfiguration().addMapper(SlowQueryMapper.class);
        }
        SlowQueryMapper slowQueryMapper =
                new SqlSessionTemplate(sqlSessionFactory).getMapper(SlowQueryMapper.class);
        assertThrows(
                IllegalStateException.class,
                () -> mapperExecutor.execute(() -> slowQueryMapper.sleep(2))
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    interface SlowQueryMapper {

        @Select("SELECT SLEEP(#{seconds})")
        int sleep(@Param("seconds") int seconds);
    }
}
