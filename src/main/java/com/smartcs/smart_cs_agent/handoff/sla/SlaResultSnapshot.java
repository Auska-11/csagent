package com.smartcs.smart_cs_agent.handoff.sla;

/**
 * 某个 requestId 当前生效的结果快照（含撤回标记）。
 *
 * <p>快照只对原商品、原买家、原会话生效；更高版本整体替换，低版本不得回退。
 * WITHDRAWN 作为新版本覆盖旧结论，消费方据此停止引用旧结果。</p>
 */
public record SlaResultSnapshot(
        String requestId,
        long workItemId,
        int version,
        String scope,
        String outcome,
        String content,
        String recordedBy,
        String recordedAt,
        long updatedAt
) {
}
