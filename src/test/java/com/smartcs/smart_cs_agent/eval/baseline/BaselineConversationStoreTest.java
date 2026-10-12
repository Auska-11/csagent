package com.smartcs.smart_cs_agent.eval.baseline;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineConversationStoreTest {

    @Test
    void sameConversationIdMustNotShareHistoryAcrossUsersOrTenants() {
        BaselineConversationStore store = new BaselineConversationStore();
        ConversationKey owner = new ConversationKey("tenant-a", "user-a", "shared-conversation");
        ConversationKey otherUser = new ConversationKey("tenant-a", "user-b", "shared-conversation");
        ConversationKey otherTenant = new ConversationKey("tenant-b", "user-a", "shared-conversation");
        UserMessage privateMessage = UserMessage.from("private order information");
        store.append(owner, privateMessage);

        assertEquals(List.of(privateMessage), store.historyOf(owner));
        assertTrue(store.historyOf(otherUser).isEmpty());
        assertTrue(store.historyOf(otherTenant).isEmpty());
        store.append(otherTenant, UserMessage.from("another tenant's message"));
        assertEquals(List.of(privateMessage), store.historyOf(owner));
    }

    @Test
    void experimentMustRefusePersistentOrRemoteBusinessData() {
        assertThrows(IllegalStateException.class, () -> new BaselineFunctionCallingAgent(
                null, null, null, null, null, null, 30_000, 8, "jdbc", "local", "memory"));
        assertThrows(IllegalStateException.class, () -> new BaselineFunctionCallingAgent(
                null, null, null, null, null, null, 30_000, 8, "memory", "hybrid", "memory"));
        assertThrows(IllegalStateException.class, () -> new BaselineFunctionCallingAgent(
                null, null, null, null, null, null, 30_000, 8, "memory", "mcp-remote", "memory"));
        assertThrows(IllegalStateException.class, () -> new BaselineFunctionCallingAgent(
                null, null, null, null, null, null, 30_000, 8, "memory", "local", "after-sales-http"));
    }
}
