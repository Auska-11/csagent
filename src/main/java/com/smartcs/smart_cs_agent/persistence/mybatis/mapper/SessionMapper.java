package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.persistence.mybatis.row.SessionRow;
import org.apache.ibatis.annotations.Param;

public interface SessionMapper {

    void createTable();

    SessionRow find(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationId") String conversationId);

    int insert(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("stateJson") String stateJson,
            @Param("schemaVersion") int schemaVersion,
            @Param("version") long version,
            @Param("now") long now);

    int updateCas(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("stateJson") String stateJson,
            @Param("schemaVersion") int schemaVersion,
            @Param("nextVersion") long nextVersion,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") long now);

    int deleteCas(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("expectedVersion") long expectedVersion);
}
