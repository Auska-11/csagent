package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final JdbcDatabaseClient databaseClient;

    public OrderRepository() {
        this(null);
    }

    @Autowired
    public OrderRepository(JdbcDatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
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
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_order (
                    order_id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    product_id VARCHAR(64) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    tracking_no VARCHAR(128)
                )
                """);

        // 第一版使用 upsert 写入演示数据，保证重复启动不会插入重复订单。
        seedJdbc(new Order("ORD-001", "USER-001", "PROD-001", "已发货", "SF1234567890"));
        seedJdbc(new Order("ORD-002", "USER-001", "PROD-002", "待发货", ""));
        seedJdbc(new Order("ORD-003", "USER-002", "PROD-003", "已签收", "YT9876543210"));
        seedJdbc(new Order("ORD-004", "USER-002", "PROD-004", "已签收", "ZT1111111111"));
    }

    private void seedJdbc(Order order) {
        databaseClient.update("""
                INSERT INTO cs_order(order_id, user_id, product_id, status, tracking_no)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    user_id = VALUES(user_id),
                    product_id = VALUES(product_id),
                    status = VALUES(status),
                    tracking_no = VALUES(tracking_no)
                """,
                order.getOrderId(),
                order.getUserId(),
                order.getProductId(),
                order.getStatus(),
                order.getTrackingNo());
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
            return databaseClient.queryOne("""
                            SELECT order_id, user_id, product_id, status, tracking_no
                            FROM cs_order
                            WHERE order_id = ?
                            """,
                    resultSet -> new Order(
                            resultSet.getString("order_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("product_id"),
                            resultSet.getString("status"),
                            resultSet.getString("tracking_no")
                    ),
                    orderId).orElse(null);
        }
        return orderTable.get(orderId);
    }

    /**
     * 根据用户ID查询所有订单
     */
    public List<Order> findByUserId(String userId) {
        if (isJdbcMode()) {
            return databaseClient.queryList("""
                            SELECT order_id, user_id, product_id, status, tracking_no
                            FROM cs_order
                            WHERE user_id = ?
                            ORDER BY order_id
                            """,
                    resultSet -> new Order(
                            resultSet.getString("order_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("product_id"),
                            resultSet.getString("status"),
                            resultSet.getString("tracking_no")
                    ),
                    userId);
        }
        return orderTable.values().stream()
            .filter(o -> o.getUserId().equals(userId))
            .collect(Collectors.toList());
    }

    private boolean isJdbcMode() {
        return databaseClient != null && databaseClient.isEnabled();
    }
}
