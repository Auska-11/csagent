package com.smartcs.smart_cs_agent.session.jdbc;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.SessionMapper;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.SessionRow;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionStore;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.core.ConversationStateCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/** MySQL 会话持久化适配器。 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcSessionStore implements SessionStore {

    private final SessionMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;
    private final ConversationStateCodec codec;

    public JdbcSessionStore(SessionMapper mapper,
                            MyBatisMapperExecutor mapperExecutor,
                            ConversationStateCodec codec) {
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.mapperExecutor = Objects.requireNonNull(
                mapperExecutor,
                "mapperExecutor cannot be null"
        );
        this.codec = codec;
        initTable();
    }

    private void initTable() {
        mapperExecutor.run(mapper::createTable);
    }

    @Override
    public Optional<SessionRecord> load(ConversationKey key) {
        SessionRow row = mapperExecutor.execute(() -> mapper.find(
                key.tenantId(),
                key.userId(),
                key.conversationId()
        ));
        if (row == null) {
            return Optional.empty();
        }
        ConversationStateSnapshot snapshot = codec.decode(row.stateJson(), row.schemaVersion());
        return Optional.of(new SessionRecord(key, snapshot, row.version(), row.updatedAt()));
    }

    @Override
    public SessionRecord save(
            ConversationKey key,
            long expectedVersion,
            ConversationStateSnapshot snapshot) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }

        // SessionStore 是公开 SPI，不能假设每个调用方都先经过 ConversationStore。
        // 所有权必须在 JSON 编码和任何 INSERT/UPDATE 之前校验；否则虽然返回异常，错误数据却已经入库。
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (!key.userId().equals(snapshot.userId())) {
            throw new IllegalArgumentException("session snapshot owner does not match key");
        }

        String stateJson = codec.encode(snapshot);
        long now = System.currentTimeMillis();
        long nextVersion = expectedVersion + 1;
        int changedRows;

        if (expectedVersion == 0) {
            try {
                changedRows = mapperExecutor.execute(() -> mapper.insert(
                        key.tenantId(),
                        key.userId(),
                        key.conversationId(),
                        stateJson,
                        snapshot.schemaVersion(),
                        nextVersion,
                        now
                ));
            } catch (JdbcDuplicateKeyException duplicateKey) {
                // 复合主键已被另一个执行者创建，读取实际版本后转成统一 CAS 冲突。
                Optional<SessionRecord> existing = load(key);
                if (existing.isPresent()) {
                    throw new SessionVersionConflictException(
                            key,
                            expectedVersion,
                            existing.get().version()
                    );
                }
                throw duplicateKey;
            }
        } else {
            changedRows = mapperExecutor.execute(() -> mapper.updateCas(
                    key.tenantId(),
                    key.userId(),
                    key.conversationId(),
                    stateJson,
                    snapshot.schemaVersion(),
                    nextVersion,
                    expectedVersion,
                    now
            ));
        }

        if (changedRows != 1) {
            long actualVersion = load(key).map(SessionRecord::version).orElse(0L);
            throw new SessionVersionConflictException(key, expectedVersion, actualVersion);
        }
        return new SessionRecord(key, snapshot, nextVersion, now);
    }

    @Override
    public boolean delete(ConversationKey key, long expectedVersion) {
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        int changedRows = mapperExecutor.execute(() -> mapper.deleteCas(
                key.tenantId(),
                key.userId(),
                key.conversationId(),
                expectedVersion
        ));
        if (changedRows == 1) {
            return true;
        }

        Optional<SessionRecord> existing = load(key);
        if (existing.isEmpty()) {
            return false;
        }
        throw new SessionVersionConflictException(key, expectedVersion, existing.get().version());
    }
}
