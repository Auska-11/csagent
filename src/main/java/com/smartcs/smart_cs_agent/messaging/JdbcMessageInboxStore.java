package com.smartcs.smart_cs_agent.messaging;

import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.MessageInboxMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcMessageInboxStore implements MessageInboxStore {
    private final MessageInboxMapper mapper;
    private final MyBatisMapperExecutor db;

    public JdbcMessageInboxStore(MessageInboxMapper mapper, MyBatisMapperExecutor db) {
        this.mapper = mapper;
        this.db = db;
        db.run(mapper::createTable);
    }

    @Override
    public boolean claim(String key, String payloadHash, long now) {
        try {
            return db.execute(() -> mapper.insert(key, payloadHash, now)) == 1;
        } catch (JdbcDuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public Optional<Entry> find(String key) { return Optional.ofNullable(db.execute(() -> mapper.find(key))); }

    @Override
    public void complete(String key, String responseJson) {
        if (db.execute(() -> mapper.complete(key, responseJson)) != 1) {
            throw new IllegalStateException("Message receipt completion lost its claim");
        }
    }

    @Override
    public void markUnknown(String key) { db.execute(() -> mapper.markUnknown(key)); }
}
