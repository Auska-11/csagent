package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.model.ReturnRecord;
import org.apache.ibatis.annotations.Param;

public interface ReturnMapper {

    void createTable();

    /** 旧表补 order_id 唯一索引的启动期迁移；索引已存在时由 DdlMigrationRunner 幂等处理。 */
    void ensureOrderUniqueKey();

    /** 创建路径专用：纯 INSERT，主键冲突向上抛 JdbcDuplicateKeyException，绝不静默覆盖。 */
    int insert(ReturnRecord record);

    /** 演示种子数据专用：可重复启动；业务创建路径禁止使用。 */
    int upsert(ReturnRecord record);

    ReturnRecord findByReturnId(@Param("returnId") String returnId);

    ReturnRecord findFirstByOrderId(@Param("orderId") String orderId);
}
