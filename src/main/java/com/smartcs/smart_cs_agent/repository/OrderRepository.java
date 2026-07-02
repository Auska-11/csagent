package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Order;
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

    public OrderRepository() {
        initMockData();
    }

    private void initMockData() {
        // 张三的订单
        save(new Order("ORD-001", "USER-001", "PROD-001", "已发货", "SF1234567890"));
        save(new Order("ORD-002", "USER-001", "PROD-002", "待发货", ""));

        // 李四的订单
        save(new Order("ORD-003", "USER-002", "PROD-003", "已签收", "YT9876543210"));
        save(new Order("ORD-004", "USER-002", "PROD-004", "已签收", "ZT1111111111"));
    }

    public void save(Order order) {
        orderTable.put(order.getOrderId(), order);
    }

    /**
     * 根据订单号查询
     */
    public Order findByOrderId(String orderId) {
        return orderTable.get(orderId);
    }

    /**
     * 根据用户ID查询所有订单
     */
    public List<Order> findByUserId(String userId) {
        return orderTable.values().stream()
            .filter(o -> o.getUserId().equals(userId))
            .collect(Collectors.toList());
    }
}