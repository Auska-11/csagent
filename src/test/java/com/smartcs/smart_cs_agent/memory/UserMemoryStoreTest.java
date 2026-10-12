package com.smartcs.smart_cs_agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

        assertTrue(store.rememberProductPreference("default", "USER-001", "蓝牙耳机"));
        assertFalse(store.rememberProductPreference("default", "USER-001", "蓝牙耳机"));

        UserMemorySnapshot snapshot = store.getSnapshot("default", "USER-001");
        assertEquals("USER-001", snapshot.getUserId());
        assertEquals(1, snapshot.getProductPreferences().size());
        assertEquals("蓝牙耳机", snapshot.getProductPreferences().get(0));
        assertEquals(1, snapshot.getVersion());
        assertTrue(snapshot.getUpdatedAt() > 0);
    }

    @Test
    void shouldReturnEmptySnapshotWhenUserHasNoMemory() {
        UserMemoryStore store = new UserMemoryStore();

        UserMemorySnapshot snapshot = store.getSnapshot("default", "USER-NONE");

        assertEquals("USER-NONE", snapshot.getUserId());
        assertTrue(snapshot.getProductPreferences().isEmpty());
        assertEquals(0, snapshot.getVersion());
        assertEquals(0, snapshot.getUpdatedAt());
    }

    @Test
    void shouldClearUserMemory() {
        UserMemoryStore store = new UserMemoryStore();
        store.rememberProductPreference("default", "USER-001", "蓝牙耳机");

        assertTrue(store.clear("default", "USER-001"));
        assertFalse(store.clear("default", "USER-001"));
        assertTrue(store.getSnapshot("default", "USER-001").getProductPreferences().isEmpty());
    }

    @Test
    void shouldExpirePreferencesAndRefreshTtlWhenDuplicateIsSeenAgain() {
        MemorySafetyProperties properties = properties(1_000, 10, 10, 80);
        MutableClock clock = new MutableClock(10_000);
        UserMemoryStore store = new UserMemoryStore(properties, clock);

        assertTrue(store.rememberProductPreference("default", "USER-001", "蓝牙耳机"));
        clock.advanceMillis(900);
        assertFalse(store.rememberProductPreference("default", "USER-001", "蓝牙耳机"));

        // 重复表达会刷新最后出现时间，因此不能按第一次写入时间过期。
        clock.advanceMillis(999);
        assertTrue(store.getSnapshot("default", "USER-001").hasProductPreferences());
        clock.advanceMillis(1);
        assertFalse(store.getSnapshot("default", "USER-001").hasProductPreferences());
    }

    @Test
    void shouldEvictOldestPreferenceAndLeastRecentlyUsedUserAtCapacity() {
        MemorySafetyProperties properties = properties(100_000, 2, 2, 80);
        MutableClock clock = new MutableClock(10_000);
        UserMemoryStore store = new UserMemoryStore(properties, clock);

        store.rememberProductPreference("default", "USER-001", "蓝牙耳机");
        clock.advanceMillis(1);
        store.rememberProductPreference("default", "USER-001", "智能手表");
        clock.advanceMillis(1);
        store.rememberProductPreference("default", "USER-001", "机械键盘");
        assertEquals(
                java.util.List.of("智能手表", "机械键盘"),
                store.getSnapshot("default", "USER-001").getProductPreferences()
        );

        store.rememberProductPreference("default", "USER-002", "显示器");
        store.getSnapshot("default", "USER-001"); // 读取 USER-001，让它成为最近使用者。
        store.rememberProductPreference("default", "USER-003", "鼠标");

        assertTrue(store.getSnapshot("default", "USER-001").hasProductPreferences());
        assertFalse(store.getSnapshot("default", "USER-002").hasProductPreferences());
        assertTrue(store.getSnapshot("default", "USER-003").hasProductPreferences());
    }

    @Test
    void shouldRedactSensitiveTextRejectSensitiveOnlyInputAndLimitUnicodeLength() {
        MemorySafetyProperties properties = properties(100_000, 10, 10, 80);
        UserMemoryStore store = new UserMemoryStore(properties, new MutableClock(10_000));

        UserMemoryWriteResult accepted = store.rememberProductPreferenceSafely(
                "default",
                "USER-001",
                "蓝牙耳机，手机号 13800138000，邮箱 buyer@example.com"
        );
        assertTrue(accepted.accepted());
        assertTrue(accepted.storedPreference().contains("[REDACTED]"));
        assertFalse(accepted.storedPreference().contains("13800138000"));
        assertFalse(accepted.storedPreference().contains("buyer@example.com"));

        UserMemoryWriteResult rejected = store.rememberProductPreferenceSafely(
                "default",
                "USER-002",
                "我的手机号是 13800138000"
        );
        assertFalse(rejected.accepted());
        assertFalse(store.getSnapshot("default", "USER-002").hasProductPreferences());

        MemorySafetyProperties shortProperties = properties(100_000, 10, 10, 4);
        UserMemoryStore shortStore = new UserMemoryStore(shortProperties, new MutableClock(10_000));
        UserMemoryWriteResult shortened = shortStore.rememberProductPreferenceSafely(
                "default",
                "USER-003",
                "耳机😀😀😀"
        );
        assertTrue(shortened.accepted());
        assertEquals(4, shortened.storedPreference().codePointCount(
                0,
                shortened.storedPreference().length()
        ));

        UserMemoryWriteResult shortSensitiveOnly = shortStore.rememberProductPreferenceSafely(
                "default",
                "USER-004",
                "13800138000"
        );
        assertFalse(shortSensitiveOnly.accepted());
        assertFalse(shortStore.getSnapshot("default", "USER-004").hasProductPreferences());
    }

    @Test
    void sameUserIdMustHaveIndependentPreferencesAndDeletionAcrossTenants() {
        UserMemoryStore store = new UserMemoryStore();
        store.rememberProductPreference("tenant-a", "shared-user", "蓝牙耳机");
        store.rememberProductPreference("tenant-b", "shared-user", "智能手表");

        assertEquals(java.util.List.of("蓝牙耳机"),
                store.getSnapshot("tenant-a", "shared-user").getProductPreferences());
        assertEquals(java.util.List.of("智能手表"),
                store.getSnapshot("tenant-b", "shared-user").getProductPreferences());
        assertTrue(store.clear("tenant-b", "shared-user"));
        assertFalse(store.getSnapshot("tenant-b", "shared-user").hasProductPreferences());
        assertEquals(java.util.List.of("蓝牙耳机"),
                store.getSnapshot("tenant-a", "shared-user").getProductPreferences());
    }

    private MemorySafetyProperties properties(
            long ttlMs,
            int maxUsers,
            int maxPreferences,
            int maxPreferenceLength) {
        MemorySafetyProperties properties = new MemorySafetyProperties();
        properties.setPreferenceTtlMs(ttlMs);
        properties.setMaxUsers(maxUsers);
        properties.setMaxPreferencesPerUser(maxPreferences);
        properties.setMaxPreferenceLength(maxPreferenceLength);
        return properties;
    }

    /** 可手工推进的时钟，让 TTL 测试保持毫秒级且不依赖 sleep。 */
    private static final class MutableClock extends Clock {

        private long currentMillis;
        private final ZoneId zone;

        private MutableClock(long currentMillis) {
            this(currentMillis, ZoneOffset.UTC);
        }

        private MutableClock(long currentMillis, ZoneId zone) {
            this.currentMillis = currentMillis;
            this.zone = zone;
        }

        private void advanceMillis(long millis) {
            currentMillis += millis;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentMillis, zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis);
        }

        @Override
        public long millis() {
            return currentMillis;
        }
    }
}
