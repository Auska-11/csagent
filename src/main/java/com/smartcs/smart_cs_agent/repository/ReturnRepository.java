package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * 退换货数据仓库
 */
@Repository
public class ReturnRepository {

    // 模拟退换货表：returnId -> ReturnRecord 对象
    private final Map<String, ReturnRecord> returnTable = new HashMap<>();

    private final JdbcDatabaseClient databaseClient;

    public ReturnRepository() {
        this(null);
    }

    @Autowired
    public ReturnRepository(JdbcDatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
        if (isJdbcMode()) {
            initJdbcTable();
        } else {
            initMockData();
        }
    }

    private void initMockData() {
        // 李四的数据线有质量问题，申请了退换货
        save(new ReturnRecord("RET-001", "ORD-004", "数据线接口松动，充电不稳定", "审核通过"));
    }

    private void initJdbcTable() {
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_return_record (
                    return_id VARCHAR(64) PRIMARY KEY,
                    order_id VARCHAR(64) NOT NULL,
                    reason TEXT,
                    status VARCHAR(64) NOT NULL
                )
                """);

        seedJdbc(new ReturnRecord("RET-001", "ORD-004", "数据线接口松动，充电不稳定", "审核通过"));
    }

    private void seedJdbc(ReturnRecord record) {
        databaseClient.update("""
                INSERT INTO cs_return_record(return_id, order_id, reason, status)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    order_id = VALUES(order_id),
                    reason = VALUES(reason),
                    status = VALUES(status)
                """,
                record.getReturnId(),
                record.getOrderId(),
                record.getReason(),
                record.getStatus());
    }

    public void save(ReturnRecord record) {
        if (isJdbcMode()) {
            seedJdbc(record);
            return;
        }
        returnTable.put(record.getReturnId(), record);
    }

    /**
     * 根据退换单号查询
     */
    public ReturnRecord findByReturnId(String returnId) {
        if (isJdbcMode()) {
            return databaseClient.queryOne("""
                            SELECT return_id, order_id, reason, status
                            FROM cs_return_record
                            WHERE return_id = ?
                            """,
                    resultSet -> new ReturnRecord(
                            resultSet.getString("return_id"),
                            resultSet.getString("order_id"),
                            resultSet.getString("reason"),
                            resultSet.getString("status")
                    ),
                    returnId).orElse(null);
        }
        return returnTable.get(returnId);
    }

    /**
     * 根据订单号查询退换货记录
     */
    public ReturnRecord findByOrderId(String orderId) {
        if (isJdbcMode()) {
            return databaseClient.queryOne("""
                            SELECT return_id, order_id, reason, status
                            FROM cs_return_record
                            WHERE order_id = ?
                            ORDER BY return_id
                            LIMIT 1
                            """,
                    resultSet -> new ReturnRecord(
                            resultSet.getString("return_id"),
                            resultSet.getString("order_id"),
                            resultSet.getString("reason"),
                            resultSet.getString("status")
                    ),
                    orderId).orElse(null);
        }
        return returnTable.values().stream()
            .filter(r -> r.getOrderId().equals(orderId))
            .findFirst()
            .orElse(null);
    }

    /**
     * 生成新的退换单号
     */
    public String generateReturnId() {
        if (isJdbcMode()) {
            long count = databaseClient.queryLong("SELECT COUNT(*) FROM cs_return_record");
            return "RET-" + String.format("%03d", count + 1);
        }
        return "RET-" + String.format("%03d", returnTable.size() + 1);
    }

    private boolean isJdbcMode() {
        return databaseClient != null && databaseClient.isEnabled();
    }
}
