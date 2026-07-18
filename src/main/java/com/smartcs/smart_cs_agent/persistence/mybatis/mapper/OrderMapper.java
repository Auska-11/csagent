package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.model.Order;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OrderMapper {

    void createTable();

    int upsert(Order order);

    Order findByOrderId(@Param("orderId") String orderId);

    List<Order> findByUserId(@Param("userId") String userId);
}
