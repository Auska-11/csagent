package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record PendingHandoffClaimRow(String ownerToken, long leaseUntil) {
}
