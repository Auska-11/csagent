package com.smartcs.smart_cs_agent.memory;

import com.smartcs.smart_cs_agent.security.SensitiveTextRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆仓库。
 *
 * 【为什么先用内存实现？】
 * 当前项目还没有把用户记忆接到 MySQL / Redis，所以仍使用进程内 Map。
 * 但内存实现也必须有 TTL、容量和脱敏边界，不能因为是 Demo 就允许数据无限增长。
 *
 * 后续持久化时应先抽出 UserMemoryRepository 一类的 SPI，再提供 JDBC / Redis 实现；
 * Workflow 依赖接口后，Runtime 才能在不感知存储细节的情况下切换实现。
 */
@Component
public class UserMemoryStore {

    /**
     * userId -> 用户长期记忆，accessOrder=true 表示读取或写入后会移动到末尾。
     * 达到 maxUsers 时，迭代器最前面的用户就是最久未使用者。
     */
    private final Map<String, MutableUserMemory> memories = new LinkedHashMap<>(16, 0.75f, true);

    private final long preferenceTtlMs;
    private final int maxUsers;
    private final int maxPreferencesPerUser;
    private final int maxPreferenceLength;
    private final Clock clock;

    /** Spring 运行时注入 application.yml 中的 Memory 安全策略。 */
    @Autowired
    public UserMemoryStore(MemorySafetyProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 兼容不启动 Spring 的旧测试和 Legacy Skill 构造器。 */
    public UserMemoryStore() {
        this(new MemorySafetyProperties(), Clock.systemUTC());
    }

    /** 测试可以传入可控 Clock，不需要真的 sleep 等待 TTL。 */
    public UserMemoryStore(MemorySafetyProperties properties, Clock clock) {
        if (properties == null) {
            throw new IllegalArgumentException("memory properties cannot be null");
        }
        this.clock = java.util.Objects.requireNonNull(clock, "clock cannot be null");
        this.preferenceTtlMs = properties.getPreferenceTtlMs();
        this.maxUsers = properties.getMaxUsers();
        this.maxPreferencesPerUser = properties.getMaxPreferencesPerUser();
        this.maxPreferenceLength = properties.getMaxPreferenceLength();
    }

    /**
     * 记录一个商品偏好。
     *
     * @return true 表示新增了偏好；false 表示重复偏好，或输入因为空/敏感信息被拒绝
     */
    public boolean rememberProductPreference(String userId, String preference) {
        return rememberProductPreferenceSafely(userId, preference).added();
    }

    /**
     * 写入脱敏后的商品偏好。
     *
     * <p>accepted=false 表示输入为空，或脱敏后只剩手机号/邮箱等敏感占位符；调用方不能继续把原始
     * preference 写进 Trace、任务状态或回复。</p>
     */
    public synchronized UserMemoryWriteResult rememberProductPreferenceSafely(
            String userId,
            String preference) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null || preference == null || preference.isBlank()) {
            return UserMemoryWriteResult.rejected();
        }

        String normalizedPreference = SensitiveTextRedactor.normalizeSingleLine(preference);
        boolean sensitiveContentWasRedacted = SensitiveTextRedactor.containsRedactionMarker(
                normalizedPreference
        );
        String safePreference = SensitiveTextRedactor.limitSingleLine(
                normalizedPreference,
                maxPreferenceLength
        );
        if (!SensitiveTextRedactor.hasMeaningfulContent(
                safePreference,
                sensitiveContentWasRedacted
        )) {
            return UserMemoryWriteResult.rejected();
        }

        long now = clock.millis();
        purgeExpiredUsers(now);
        MutableUserMemory memory = memories.get(normalizedUserId);
        if (memory == null) {
            evictOldestUserIfFull();
            memory = new MutableUserMemory(normalizedUserId);
            memories.put(normalizedUserId, memory);
        }

        boolean added = memory.remember(
                safePreference,
                now,
                preferenceTtlMs,
                maxPreferencesPerUser
        );
        return UserMemoryWriteResult.accepted(added, safePreference);
    }

    /**
     * 获取用户长期记忆快照。
     *
     * 即使用户还没有长期记忆，也返回一个空快照，避免调用方到处判空。
     */
    public synchronized UserMemorySnapshot getSnapshot(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return emptySnapshot(userId);
        }

        MutableUserMemory memory = memories.get(normalizedUserId);
        if (memory == null) {
            return emptySnapshot(normalizedUserId);
        }

        memory.purgeExpired(clock.millis(), preferenceTtlMs);
        if (memory.isEmpty()) {
            memories.remove(normalizedUserId);
            return emptySnapshot(normalizedUserId);
        }
        return memory.snapshot();
    }

    /**
     * 清空某个用户的长期记忆。
     *
     * 第一版先用于测试和调试；后续如果前端需要“清除记忆”按钮，可以直接复用。
     */
    public synchronized boolean clear(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return false;
        }
        return memories.remove(normalizedUserId) != null;
    }

    private void purgeExpiredUsers(long now) {
        Iterator<Map.Entry<String, MutableUserMemory>> iterator = memories.entrySet().iterator();
        while (iterator.hasNext()) {
            MutableUserMemory memory = iterator.next().getValue();
            memory.purgeExpired(now, preferenceTtlMs);
            if (memory.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void evictOldestUserIfFull() {
        if (memories.size() < maxUsers) {
            return;
        }
        Iterator<String> iterator = memories.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userId.trim();
    }

    private UserMemorySnapshot emptySnapshot(String userId) {
        return new UserMemorySnapshot(userId, List.of(), 0, 0);
    }

    /**
     * 内部可变记忆对象。
     *
     * 对外只暴露 UserMemorySnapshot，避免外部代码直接修改内部集合。
     */
    private static class MutableUserMemory {

        private final String userId;

        /**
         * value 是最后表达时间。重复偏好会 remove + put，从而移动到末尾并刷新 TTL。
         */
        private final LinkedHashMap<String, Long> productPreferences = new LinkedHashMap<>();

        private int version;
        private long updatedAt;

        private MutableUserMemory(String userId) {
            this.userId = userId;
        }

        private boolean remember(
                String preference,
                long now,
                long ttlMs,
                int maxPreferences) {
            purgeExpired(now, ttlMs);
            boolean added = productPreferences.remove(preference) == null;
            productPreferences.put(preference, now);

            while (productPreferences.size() > maxPreferences) {
                Iterator<String> iterator = productPreferences.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            if (added) {
                version++;
            }
            updatedAt = now;
            return added;
        }

        private void purgeExpired(long now, long ttlMs) {
            int sizeBefore = productPreferences.size();
            productPreferences.entrySet().removeIf(entry -> isExpired(entry.getValue(), now, ttlMs));
            if (productPreferences.size() != sizeBefore) {
                version++;
                updatedAt = now;
            }
        }

        private boolean isExpired(long lastSeenAt, long now, long ttlMs) {
            return now >= lastSeenAt && now - lastSeenAt >= ttlMs;
        }

        private boolean isEmpty() {
            return productPreferences.isEmpty();
        }

        private UserMemorySnapshot snapshot() {
            return new UserMemorySnapshot(
                    userId,
                    new ArrayList<>(productPreferences.keySet()),
                    version,
                    updatedAt
            );
        }
    }
}
