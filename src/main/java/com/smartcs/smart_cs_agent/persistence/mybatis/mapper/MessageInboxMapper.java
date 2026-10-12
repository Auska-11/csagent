package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.messaging.MessageInboxStore;
import org.apache.ibatis.annotations.Param;

public interface MessageInboxMapper {
    void createTable();
    int insert(@Param("key") String key, @Param("hash") String hash, @Param("now") long now);
    MessageInboxStore.Entry find(@Param("key") String key);
    int complete(@Param("key") String key, @Param("response") String response);
    int markUnknown(@Param("key") String key);
}
