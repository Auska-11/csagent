package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.model.User;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {

    void createTable();

    int upsert(User user);

    User findByUserId(@Param("userId") String userId);

    User findByToken(@Param("token") String token);
}
