package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.User;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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

    // Legacy token 索引：仅供 Phase 9 前的旧 Tool 兼容，不参与 HTTP/MCP 认证。
    private final Map<String, String> tokenIndex = new HashMap<>();

    private final UserMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;

    public UserRepository() {
        this.mapper = null;
        this.mapperExecutor = new MyBatisMapperExecutor();
        initMockData();
    }

    @Autowired
    public UserRepository(ObjectProvider<UserMapper> mapperProvider,
                          MyBatisMapperExecutor mapperExecutor) {
        this.mapper = mapperProvider.getIfAvailable();
        this.mapperExecutor = mapperExecutor;
        if (isJdbcMode()) {
            initJdbcTable();
        } else {
            // 初始化模拟数据
            initMockData();
        }
    }

    /**
     * 初始化模拟数据
     * 真实项目中这里会是数据库初始化或数据迁移
     */
    private void initMockData() {
        save(new User("USER-001", "张三", "13812341234", "token-zhangsan"));
        save(new User("USER-002", "李四", "13956785678", "token-lisi"));
    }

    private void initJdbcTable() {
        mapperExecutor.run(mapper::createTable);

        seedJdbc(new User("USER-001", "张三", "13812341234", "token-zhangsan"));
        seedJdbc(new User("USER-002", "李四", "13956785678", "token-lisi"));
    }

    private void seedJdbc(User user) {
        mapperExecutor.execute(() -> mapper.upsert(user));
    }

    /**
     * 保存用户
     */
    public void save(User user) {
        if (isJdbcMode()) {
            seedJdbc(user);
            return;
        }
        userTable.put(user.getUserId(), user);
        tokenIndex.put(user.getToken(), user.getUserId());
    }

    /**
     * 根据 userId 查询用户
     */
    public User findByUserId(String userId) {
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.findByUserId(userId));
        }
        return userTable.get(userId);
    }

    /**
     * 根据旧演示 token 查询用户。
     *
     * 当前认证已经统一由 Spring Security JWT 完成；新代码应按 Principal 的 userId 调用
     * findByUserId，不应把这个方法当登录校验器。
     */
    public User findByToken(String token) {
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.findByToken(token));
        }
        String userId = tokenIndex.get(token);
        return userId != null ? userTable.get(userId) : null;
    }

    private boolean isJdbcMode() {
        return mapper != null;
    }
}
