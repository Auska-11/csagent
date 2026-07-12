package com.smartcs.smart_cs_agent.workflow.escalation;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工升级 Workflow。
 *
 * <p>它统一接管三类需要停止自动处理的场景：用户明确要求人工、投诉，以及赔偿/维权纠纷。
 * 原计划中的 ComplaintWorkflow 不再单独实现，因为投诉最终也执行同一个 HandoffAction；拆成两个
 * Workflow 只会复制匹配、摘要、权限和工单测试。</p>
 *
 * <p>本类只负责“是否升级、为什么升级、给人工什么摘要”。真正创建工单由 HandoffActionHandler
 * 和 handoff.request Capability 完成，因此本 Workflow 不依赖 ToolRegistry、HTTP Client 或工单 DTO。</p>
 */
@Component
public class EscalationWorkflow implements AgentWorkflow {

    public static final String ID = "escalation";
    public static final String VERSION = "1.0.0";

    private static final String ACTIVE_INTENT = "handoff";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "处理明确人工请求、投诉和赔偿维权纠纷",
            250
    );

    private final GlobalInterruptPolicy interruptPolicy;

    public EscalationWorkflow(GlobalInterruptPolicy interruptPolicy) {
        this.interruptPolicy = interruptPolicy;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 首次路由只做无副作用判断。
     *
     * <p>“什么情况可以转人工”会被 GlobalInterruptPolicy 明确识别为政策问题，因此这里返回 0 分，
     * 后续由 PolicyQaWorkflow 查询知识库，而不会因为包含“人工”两个字就创建工单。</p>
     */
    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        GlobalInterruptDecision decision = interruptPolicy.evaluate(context.getMessage());
        if (decision.getType() == GlobalInterruptType.HANDOFF) {
            return new WorkflowMatchResult(
                    1.0,
                    "命中人工升级信号：" + decision.getHandoffReasonCode()
            );
        }
        if (interruptPolicy.isHandoffPolicyQuestion(context.getMessage())) {
            return WorkflowMatchResult.noMatch("用户询问人工转接规则，应由 PolicyQaWorkflow 回答");
        }
        return WorkflowMatchResult.noMatch("未命中明确人工请求、投诉或赔偿纠纷");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        GlobalInterruptDecision decision = interruptPolicy.evaluate(context.getMessage());
        if (decision.getType() != GlobalInterruptType.HANDOFF) {
            // 正常情况下 Router 不会让非升级消息进入本 Workflow。
            // 保留这个防御性分支，避免未来错误恢复状态时意外创建有副作用的工单。
            return WorkflowDecision.next(
                    new FinalAnswerAction("当前消息没有确认需要创建人工工单，请明确说明是否需要人工客服。"),
                    "升级 Workflow 未取得明确的人工授权信号"
            );
        }

        ConversationState state = context.getState();
        String previousIntent = state.getActiveIntent();
        String reasonCode = decision.getHandoffReasonCode();
        state.setActiveIntent(ACTIVE_INTENT);
        state.clearPendingSlot();

        Map<String, Object> handoffContext = buildHandoffContext(state, previousIntent, reasonCode);
        String summary = buildSummary(context, state, previousIntent, reasonCode);
        return WorkflowDecision.next(
                new HandoffAction(reasonCode, summary, handoffContext),
                decision.getReason()
        );
    }

    /**
     * Action context 用于 Trace 和后续调试，只放结构化任务线索，不复制完整会话历史。
     */
    private Map<String, Object> buildHandoffContext(ConversationState state,
                                                    String previousIntent,
                                                    String reasonCode) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("reason_code", reasonCode);
        context.put("active_intent", ACTIVE_INTENT);
        putIfPresent(context, "previous_intent", previousIntent);
        putIfPresent(context, "order_id", state.getTaskState("order_id"));
        putIfPresent(context, "return_id", state.getTaskState("return_id"));
        return context;
    }

    /**
     * 第一版使用确定性摘要，便于测试和审计。
     *
     * <p>摘要限制长度，避免用户一次发送超长文本后原样灌入外部工单系统。以后可以增加 LLM 摘要，
     * 但仍应保留长度、敏感信息和失败降级保护。</p>
     */
    private String buildSummary(TurnContext context,
                                ConversationState state,
                                String previousIntent,
                                String reasonCode) {
        StringBuilder summary = new StringBuilder();
        summary.append(reasonLabel(reasonCode));
        summary.append("。用户当前诉求：").append(limit(compact(context.getMessage()), 500));

        if (hasText(previousIntent) && !ACTIVE_INTENT.equals(previousIntent)) {
            summary.append("。升级前意图：").append(previousIntent);
        }
        appendTaskValue(summary, "订单号", state.getTaskState("order_id"));
        appendTaskValue(summary, "退货单号", state.getTaskState("return_id"));
        return limit(summary.toString(), 800);
    }

    private String reasonLabel(String reasonCode) {
        return switch (reasonCode) {
            case GlobalInterruptPolicy.REASON_COMPLAINT -> "用户提出投诉，需要人工客服继续处理";
            case GlobalInterruptPolicy.REASON_COMPENSATION_OR_DISPUTE ->
                    "用户提出赔偿、维权或法律纠纷，需要人工客服判断";
            case GlobalInterruptPolicy.REASON_USER_REQUESTED_HUMAN -> "用户明确要求人工客服";
            default -> "当前问题需要人工客服复核";
        };
    }

    private void appendTaskValue(StringBuilder summary, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            summary.append("。").append(label).append("：").append(value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            target.put(key, value);
        }
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
