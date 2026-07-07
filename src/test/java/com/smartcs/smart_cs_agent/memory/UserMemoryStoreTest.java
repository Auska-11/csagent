package com.smartcs.smart_cs_agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户长期记忆仓库测试。
 *
 * 这里验证的是纯内存规则，不依赖 Spring、不依赖数据库。
 */
class UserMemoryStoreTest {

    @Test
    void shouldRememberProductPreferenceAndAvoidDuplicate() {
        UserMemoryStore store = new UserMemoryStore();

        assertTrue(store.rememberProductPreference("USER-001", "蓝牙耳机"));
        assertFalse(store.rememberProductPreference("USER-001", "蓝牙耳机"));

        UserMemorySnapshot snapshot = store.getSnapshot("USER-001");
        assertEquals("USER-001", snapshot.getUserId());
        assertEquals(1, snapshot.getProductPreferences().size());
        assertEquals("蓝牙耳机", snapshot.getProductPreferences().get(0));
        assertEquals(1, snapshot.getVersion());
        assertTrue(snapshot.getUpdatedAt() > 0);
    }

    @Test
    void shouldReturnEmptySnapshotWhenUserHasNoMemory() {
        UserMemoryStore store = new UserMemoryStore();

        UserMemorySnapshot snapshot = store.getSnapshot("USER-NONE");

        assertEquals("USER-NONE", snapshot.getUserId());
        assertTrue(snapshot.getProductPreferences().isEmpty());
        assertEquals(0, snapshot.getVersion());
        assertEquals(0, snapshot.getUpdatedAt());
    }
}
