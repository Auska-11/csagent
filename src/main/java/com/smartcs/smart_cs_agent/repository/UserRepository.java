package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.User;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户数据仓库（模拟数据库）
 *
 * 学习要点：
 * 1. Repository 模式 - 数据访问层，封装数据操作
 * 2. 当前使用内存 Map 存储，以后可替换为真实数据库
 */
@Repository
public class UserRepository {

    // 模拟用户表：userId -> User 对象
    private final Map<String, User> userTable = new HashMap<>();

    // Token 索引：token -> userId（用于根据 token 快速查找用户）
    private final Map<String, String> tokenIndex = new HashMap<>();

    public UserRepository() {
        // 初始化模拟数据
        initMockData();
    }

    /**
     * 初始化模拟数据
     * 真实项目中这里会是数据库初始化或数据迁移
     */
    private void initMockData() {
        save(new User("USER-001", "张三", "13812341234", "token-zhangsan"));
        save(new User("USER-002", "李四", "13956785678", "token-lisi"));
    }

    /**
     * 保存用户
     */
    public void save(User user) {
        userTable.put(user.getUserId(), user);
        tokenIndex.put(user.getToken(), user.getUserId());
    }

    /**
     * 根据 userId 查询用户
     */
    public User findByUserId(String userId) {
        return userTable.get(userId);
    }

    /**
     * 根据 token 查询用户
     * 这是最常用的查询方式，因为前端请求通常带 token
     */
    public User findByToken(String token) {
        String userId = tokenIndex.get(token);
        return userId != null ? userTable.get(userId) : null;
    }
}