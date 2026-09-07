package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.model.Order;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderMapper {

    void createTable();

    /** 旧表补 tenant_id 列的启动期迁移；重复执行由 DdlMigrationRunner 幂等处理。 */
    void ensureTenantColumn();

    int upsert(Order order);

    Order findByOrderId(@Param("orderId") String orderId);

    List<Order> findByTenantIdAndUserId(@Param("tenantId") String tenantId, @Param("userId") String userId);
}
