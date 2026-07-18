package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.model.ReturnRecord;
import org.apache.ibatis.annotations.Param;

public interface ReturnMapper {

    void createTable();

    int upsert(ReturnRecord record);

    ReturnRecord findByReturnId(@Param("returnId") String returnId);

    ReturnRecord findFirstByOrderId(@Param("orderId") String orderId);

    long count();
}
