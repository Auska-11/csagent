package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.ReturnMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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

    private final ReturnMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;

    public ReturnRepository() {
        this.mapper = null;
        this.mapperExecutor = new MyBatisMapperExecutor();
        initMockData();
    }

    @Autowired
    public ReturnRepository(ObjectProvider<ReturnMapper> mapperProvider,
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
        // 李四的数据线有质量问题，申请了退换货
        save(new ReturnRecord("RET-001", "ORD-004", "数据线接口松动，充电不稳定", "审核通过"));
    }

    private void initJdbcTable() {
        mapperExecutor.run(mapper::createTable);

        seedJdbc(new ReturnRecord("RET-001", "ORD-004", "数据线接口松动，充电不稳定", "审核通过"));
    }

    private void seedJdbc(ReturnRecord record) {
        mapperExecutor.execute(() -> mapper.upsert(record));
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
            return mapperExecutor.execute(() -> mapper.findByReturnId(returnId));
        }
        return returnTable.get(returnId);
    }

    /**
     * 根据订单号查询退换货记录
     */
    public ReturnRecord findByOrderId(String orderId) {
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.findFirstByOrderId(orderId));
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
            long count = mapperExecutor.execute(mapper::count);
            return "RET-" + String.format("%03d", count + 1);
        }
        return "RET-" + String.format("%03d", returnTable.size() + 1);
    }

    private boolean isJdbcMode() {
        return mapper != null;
    }
}
