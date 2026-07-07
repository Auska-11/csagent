package com.smartcs.smart_cs_agent.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户长期记忆仓库。
 *
 * 【为什么先用内存实现？】
 * 当前项目还没有接 MySQL / Redis，强行引入数据库会让学习成本变高。
 * 所以第一版先用 ConcurrentHashMap 模拟长期记忆，重点把“长期记忆应该放在哪里、怎么被读取”讲清楚。
 *
 * 后续如果要持久化，只需要把这个类替换成数据库版本，上层 Skill / Runtime 不需要大改。
 */
@Component
public class UserMemoryStore {

    /** userId -> 用户长期记忆 */
    private final Map<String, MutableUserMemory> memories = new ConcurrentHashMap<>();

    /**
     * 记录一个商品偏好。
     *
     * @return true 表示新增了偏好，false 表示这个偏好以前已经记录过
     */
    public boolean rememberProductPreference(String userId, String preference) {
        if (userId == null || userId.isBlank() || preference == null || preference.isBlank()) {
            return false;
        }

        MutableUserMemory memory = memories.computeIfAbsent(userId, MutableUserMemory::new);
        return memory.addProductPreference(preference.trim());
    }

    /**
     * 获取用户长期记忆快照。
     *
     * 即使用户还没有长期记忆，也返回一个空快照，避免调用方到处判空。
     */
    public UserMemorySnapshot getSnapshot(String userId) {
        MutableUserMemory memory = memories.get(userId);
        if (memory == null) {
            return new UserMemorySnapshot(userId, List.of(), 0, 0);
        }
        return memory.snapshot();
    }

    /**
     * 清空某个用户的长期记忆。
     *
     * 第一版先用于测试和调试；后续如果前端需要“清除记忆”按钮，可以直接复用。
     */
    public void clear(String userId) {
        memories.remove(userId);
    }

    /**
     * 内部可变记忆对象。
     *
     * 对外只暴露 UserMemorySnapshot，避免外部代码直接修改内部集合。
     */
    private static class MutableUserMemory {

        private final String userId;

        /**
         * LinkedHashSet 可以同时做到：
         * - 去重：同一个偏好不会重复保存很多次。
         * - 保序：前端展示时按用户表达的先后顺序展示。
         */
        private final LinkedHashSet<String> productPreferences = new LinkedHashSet<>();

        private int version;
        private long updatedAt;

        private MutableUserMemory(String userId) {
            this.userId = userId;
        }

        private synchronized boolean addProductPreference(String preference) {
            boolean added = productPreferences.add(preference);
            if (added) {
                version++;
                updatedAt = System.currentTimeMillis();
            }
            return added;
        }

        private synchronized UserMemorySnapshot snapshot() {
            return new UserMemorySnapshot(
                    userId,
                    new ArrayList<>(productPreferences),
                    version,
                    updatedAt
            );
        }
    }
}
