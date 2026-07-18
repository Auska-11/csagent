package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单数据仓库
 */
@Repository
public class OrderRepository {

    // 模拟订单表：orderId -> Order 对象
    private final Map<String, Order> orderTable = new HashMap<>();

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
     * 根据用户ID查询所有订单
     */
    public List<Order> findByUserId(String userId) {
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.findByUserId(userId));
        }
        return orderTable.values().stream()
            .filter(o -> o.getUserId().equals(userId))
            .collect(Collectors.toList());
    }

    private boolean isJdbcMode() {
        return mapper != null;
    }
}
