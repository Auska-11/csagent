package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.ReturnRecord;
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

    public ReturnRepository() {
        initMockData();
    }

    private void initMockData() {
        // 李四的数据线有质量问题，申请了退换货
        save(new ReturnRecord("RET-001", "ORD-004", "数据线接口松动，充电不稳定", "审核通过"));
    }

    public void save(ReturnRecord record) {
        returnTable.put(record.getReturnId(), record);
    }

    /**
     * 根据退换单号查询
     */
    public ReturnRecord findByReturnId(String returnId) {
        return returnTable.get(returnId);
    }

    /**
     * 根据订单号查询退换货记录
     */
    public ReturnRecord findByOrderId(String orderId) {
        return returnTable.values().stream()
            .filter(r -> r.getOrderId().equals(orderId))
            .findFirst()
            .orElse(null);
    }

    /**
     * 生成新的退换单号
     */
    public String generateReturnId() {
        return "RET-" + String.format("%03d", returnTable.size() + 1);
    }
}