package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.persistence.DdlMigrationRunner;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.ReturnMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 退换货数据仓库
 */
@Repository
public class ReturnRepository {

    /** 进程内单调递增序列，与毫秒时间戳组合保证生成的退换单号全局唯一。 */
    private static final AtomicLong ID_SEQUENCE = new AtomicLong();

    // 模拟退换货表：returnId -> ReturnRecord 对象
    private final Map<String, ReturnRecord> returnTable = new ConcurrentHashMap<>();

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
        // 旧表补 order_id 唯一索引；索引已存在时 MySQL 报 Duplicate key name，属预期的幂等结果。
        DdlMigrationRunner.run(mapperExecutor, mapper::ensureOrderUniqueKey, "Duplicate key name");

        seedJdbc(new ReturnRecord("RET-001", "ORD-004", "数据线接口松动，充电不稳定", "审核通过"));
    }

    private void seedJdbc(ReturnRecord record) {
        mapperExecutor.execute(() -> mapper.upsert(record));
    }

    public void save(ReturnRecord record) {
        // upsert 只服务于演示种子数据的幂等写入；业务创建必须走 create()，冲突即失败。
        if (isJdbcMode()) {
            seedJdbc(record);
            return;
        }
        returnTable.put(record.getReturnId(), record);
    }

    /**
     * 创建新的退换货记录（插入语义）。
     *
     * <p>仅当单号不存在且订单尚未有退货记录时写入成功；返回 false 表示撞上
     * return_id 主键或 order_id 唯一索引，调用方应按“已存在退货申请”处理，
     * 绝不能借此覆盖任何已有记录。</p>
     */
    public boolean create(ReturnRecord record) {
        if (isJdbcMode()) {
            try {
                mapperExecutor.execute(() -> mapper.insert(record));
                return true;
            } catch (JdbcDuplicateKeyException duplicateKey) {
                return false;
            }
        }
        if (returnTable.values().stream().anyMatch(existing -> existing.getOrderId().equals(record.getOrderId()))) {
            return false;
        }
        return returnTable.putIfAbsent(record.getReturnId(), record) == null;
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
     * 生成新的退换单号。
     *
     * <p>旧实现使用 COUNT+1 / size+1：两个不同订单并发申请会读到同一个计数值，
     * 生成同一单号后被 upsert 静默覆盖，最终只留下一笔记录。现在改用
     * “毫秒时间戳 + 进程内自增序列”的组合：同进程内天然唯一，跨重启也不会与历史
     * 单号相撞；真正的并发兜底由 return_id 主键与 order_id 唯一索引提供。</p>
     */
    public String generateReturnId() {
        long timestamp = System.currentTimeMillis();
        long sequence = ID_SEQUENCE.incrementAndGet();
        return "RET-"
                + Long.toUnsignedString(timestamp, 36).toUpperCase(Locale.ROOT)
                + "-"
                + Long.toUnsignedString(sequence, 36).toUpperCase(Locale.ROOT);
    }

    private boolean isJdbcMode() {
        return mapper != null;
    }
}
