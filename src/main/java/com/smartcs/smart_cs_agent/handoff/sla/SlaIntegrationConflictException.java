package com.smartcs.smart_cs_agent.handoff.sla;

/**
 * 建单侧关联记录冲突：同一 requestId（或同租户同幂等键）对应了不同坐标。
 *
 * <p>这属于本地映射损坏或配置漂移（例如 sellerAccountId 改配置后复用旧键），不是远端错误。
 * 抛出后建单走 RESULT_UNKNOWN→人工检查，绝不能换键重建。</p>
 */
public class SlaIntegrationConflictException extends RuntimeException {

    public SlaIntegrationConflictException(String message) {
        super(message);
    }
}
