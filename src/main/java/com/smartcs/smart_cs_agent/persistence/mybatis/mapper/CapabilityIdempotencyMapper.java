package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.persistence.mybatis.row.CapabilityIdempotencyRow;
import org.apache.ibatis.annotations.Param;

public interface CapabilityIdempotencyMapper {

    void createTable();

    int insertClaim(
            @Param("keyHash") String keyHash,
            @Param("fingerprintHash") String fingerprintHash,
            @Param("status") String status,
            @Param("ownerToken") String ownerToken,
            @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    int takeOver(
            @Param("keyHash") String keyHash,
            @Param("fingerprintHash") String fingerprintHash,
            @Param("status") String status,
            @Param("oldOwnerToken") String oldOwnerToken,
            @Param("oldLeaseUntil") long oldLeaseUntil,
            @Param("newOwnerToken") String newOwnerToken,
            @Param("newLeaseUntil") long newLeaseUntil,
            @Param("now") long now);

    int complete(
            @Param("keyHash") String keyHash,
            @Param("fingerprintHash") String fingerprintHash,
            @Param("inProgressStatus") String inProgressStatus,
            @Param("completedStatus") String completedStatus,
            @Param("ownerToken") String ownerToken,
            @Param("resultJson") String resultJson,
            @Param("resultSchemaVersion") int resultSchemaVersion,
            @Param("now") long now);

    int renewLease(
            @Param("keyHash") String keyHash,
            @Param("fingerprintHash") String fingerprintHash,
            @Param("status") String status,
            @Param("ownerToken") String ownerToken,
            @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    int deleteOwnedClaim(
            @Param("keyHash") String keyHash,
            @Param("status") String status,
            @Param("ownerToken") String ownerToken);

    CapabilityIdempotencyRow find(@Param("keyHash") String keyHash);
}
