package com.smartcs.smart_cs_agent.persistence.mybatis.row;

/** Capability 幂等记录的读取投影。 */
public record CapabilityIdempotencyRow(
        String requestFingerprintHash,
        String status,
        String ownerToken,
        long leaseUntil,
        String resultJson,
        int resultSchemaVersion
) {
}
