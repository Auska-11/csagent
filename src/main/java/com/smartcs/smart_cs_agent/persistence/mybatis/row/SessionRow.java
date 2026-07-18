package com.smartcs.smart_cs_agent.persistence.mybatis.row;

/** cs_agent_session 的最小读取投影。 */
public record SessionRow(
        String stateJson,
        int schemaVersion,
        long version,
        long updatedAt
) {
}
