package com.smartcs.smart_cs_agent.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.MessageInboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "smartcs.persistence.mode=jdbc", "smartcs.persistence.jdbc.driver-class-name=org.h2.Driver",
        "smartcs.persistence.jdbc.url=jdbc:h2:mem:xianyu_message_inbox;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "smartcs.persistence.jdbc.username=sa", "smartcs.persistence.jdbc.password="
})
class JdbcMessageInboxStoreTest {
    @Autowired JdbcMessageInboxStore store;
    @Autowired MessageInboxMapper mapper;
    @Autowired MyBatisMapperExecutor db;

    @Test void completedReceiptSurvivesExecutorAndStoreRecreation() {
        String event = UUID.randomUUID().toString();
        assertEquals("original", new MessageReceiptExecutor(store, new ObjectMapper()).execute("scope", event, "body", String.class, () -> "original"));
        var restarted = new MessageReceiptExecutor(new JdbcMessageInboxStore(mapper, db), new ObjectMapper());
        assertEquals("original", restarted.execute("scope", event, "body", String.class, () -> { fail("must replay stored result"); return null; }));
    }

    @Test void abandonedClaimIsNotTakenOverAfterProcessRestart() {
        String key = UUID.randomUUID().toString();
        assertTrue(store.claim(key, "hash", System.currentTimeMillis() - 120_000));
        var restarted = new JdbcMessageInboxStore(mapper, db);
        assertFalse(restarted.claim(key, "hash", System.currentTimeMillis()));
        assertEquals("PROCESSING", restarted.find(key).orElseThrow().status());
        restarted.markUnknown(key);
        assertEquals("UNKNOWN", store.find(key).orElseThrow().status());
    }
}
