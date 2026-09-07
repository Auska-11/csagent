package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.persistence.DdlMigrationRunner;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.OrderMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 订单数据仓库
 */
@Repository
public class OrderRepository {

    // 模拟订单表：orderId -> Order 对象
    private final Map<String, Order> orderTable = new ConcurrentHashMap<>();

    private final OrderMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;

    public OrderRepository() {
        this.mapper = null;
        this.mapperExecutor = new MyBatisMapperExecutor();
        initMockData();
    }

    @Autowired
    public OrderRepository(ObjectProvider<OrderMapper> mapperProvider,
                           MyBatisMapperExecutor mapperExecutor) {
        this.mapper = mapperProvider.getIfAvailable();
        this.mapperExecutor = mapperExecutor;
        if (isJdbcMode()) {
            initJdbcTable();
        } else {
            initMockData();
        }
    }

    private void initMockData() {
        // 张三的订单
        save(new Order("ORD-001", "USER-001", "PROD-001", "已发货", "SF1234567890"));
        save(new Order("ORD-002", "USER-001", "PROD-002", "待发货", ""));

        // 李四的订单
        save(new Order("ORD-003", "USER-002", "PROD-003", "已签收", "YT9876543210"));
        save(new Order("ORD-004", "USER-002", "PROD-004", "已签收", "ZT1111111111"));
    }

    private void initJdbcTable() {
        mapperExecutor.run(mapper::createTable);
        // 旧表补 tenant_id 列；列已存在时 MySQL 报 Duplicate column name，属预期的幂等结果。
        DdlMigrationRunner.run(mapperExecutor, mapper::ensureTenantColumn, "Duplicate column name");

        // 第一版使用 upsert 写入演示数据，保证重复启动不会插入重复订单。
        seedJdbc(new Order("ORD-001", "USER-001", "PROD-001", "已发货", "SF1234567890"));
        seedJdbc(new Order("ORD-002", "USER-001", "PROD-002", "待发货", ""));
        seedJdbc(new Order("ORD-003", "USER-002", "PROD-003", "已签收", "YT9876543210"));
        seedJdbc(new Order("ORD-004", "USER-002", "PROD-004", "已签收", "ZT1111111111"));
    }

    private void seedJdbc(Order order) {
        mapperExecutor.execute(() -> mapper.upsert(order));
    }

    public void save(Order order) {
        if (isJdbcMode()) {
            seedJdbc(order);
            return;
        }
        orderTable.put(order.getOrderId(), order);
    }

    /**
     * 根据订单号查询
     */
    public Order findByOrderId(String orderId) {
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.findByOrderId(orderId));
        }
        return orderTable.get(orderId);
    }

    /**
     * 按租户 + 用户查询订单。
     *
     * <p>订单归属必须同时匹配 tenantId 和 userId：如果未来两个租户出现相同用户 ID，
     * 只按 userId 过滤会让双方看到彼此的订单。</p>
     */
    public List<Order> findByTenantIdAndUserId(String tenantId, String userId) {
        String normalizedTenant = tenantId == null || tenantId.isBlank()
                ? Order.DEFAULT_TENANT_ID
                : tenantId.trim();
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.findByTenantIdAndUserId(normalizedTenant, userId));
        }
        return orderTable.values().stream()
            .filter(o -> normalizedTenant.equals(o.getTenantId()) && o.getUserId().equals(userId))
            .collect(Collectors.toList());
    }

    private boolean isJdbcMode() {
        return mapper != null;
    }
}
