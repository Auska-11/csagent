package com.smartcs.smart_cs_agent.handoff.sla;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * SLA 核实结果的消费端查询：把 Store 里的关联/快照翻译成回答路径可直接使用的口径。
 *
 * <p>契约第一阶段的结果只对原商品、原买家、原会话生效，因此查询必须同时校验 itemId；
 * 同会话切换商品后不得引用旧结论。快照按最新版本整体替换：WITHDRAWN 覆盖旧结论，
 * 消费方必须停止引用；UNABLE_TO_VERIFY 表示卖家无法核实，同样不能当作结论使用。</p>
 *
 * <p>本服务只读不写，也不触发任何买家消息或机器人恢复——消费结果的动作
 * （把结论注入下一次回答）发生在买家主动再来提问时。</p>
 */
@Component
public class SlaVerificationQueryService {

    private final SlaIntegrationStore store;

    public SlaVerificationQueryService(SlaIntegrationStore store) {
        this.store = store;
    }

    /**
     * 最近一次针对 (tenantId, conversationId, itemId) 的商品核实。
     *
     * <p>itemId 为空或不匹配最新关联时视为没有核实：结果只对原商品生效。</p>
     */
    public Optional<SlaIntegrationStore.ConversationVerification> findLatestForItem(
            String tenantId, String conversationId, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        return store.findLatestVerification(tenantId, conversationId)
                .filter(verification -> itemId.equals(verification.association().itemId()));
    }

    /**
     * 生成注入回答 Prompt 的核实结果说明；没有生效快照（或 outcome 未知）时返回 null。
     */
    public String buildResultGuidance(SlaIntegrationStore.ConversationVerification verification) {
        if (verification == null || verification.currentResult() == null) {
            return null;
        }
        SlaResultSnapshot snapshot = verification.currentResult();
        return switch (snapshot.outcome()) {
            case "CONFIRMED" -> """
                    卖家已实际核实，结论如下（仅对本商品、本买家、本会话生效）：
                    %s
                    买家问到该功能时以这条结论为准；它优先于商品描述中的“未测试”标记。"""
                    .formatted(snapshot.content());
            case "WITHDRAWN" -> """
                    注意：此前的卖家核实结论已被撤回作废。回答时不得引用任何旧结论，
                    只能按商品事实如实说明（例如功能未测试、以商品页面为准）。""";
            case "UNABLE_TO_VERIFY" -> """
                    注意：卖家已尝试核实但无法确认该功能状态。回答时不得引用任何具体结论，
                    只能如实说明卖家暂时无法核实，建议以商品页面或到货后实际验证为准。""";
            default -> null;
        };
    }
}
