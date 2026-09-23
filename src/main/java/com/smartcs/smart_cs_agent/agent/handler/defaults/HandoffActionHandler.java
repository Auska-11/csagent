package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.HandoffTicketSummary;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** 创建人工工单并结束自动 Workflow 的 Action 插件。 */
@Component
public class HandoffActionHandler implements ActionHandler<HandoffAction> {

    private static final String HANDOFF_CAPABILITY = CapabilityIds.HANDOFF_REQUEST;
    private static final long DEFAULT_UPSTREAM_TIMEOUT_MS = 30_000;

    private final CapabilityGateway capabilityGateway;
    private final SkillManager skillManager;

    public HandoffActionHandler(CapabilityGateway capabilityGateway, SkillManager skillManager) {
        this.capabilityGateway = capabilityGateway;
        this.skillManager = skillManager;
    }

    @Override
    public Class<HandoffAction> actionType() {
        return HandoffAction.class;
    }

    @Override
    public ActionExecutionResult handle(HandoffAction action, ActionHandlingContext handlingContext) {
        TurnContext context = handlingContext.getTurnContext();
        AgentSkillPackage skillPackage = handlingContext.getSkillPackage();

        Map<String, Object> handoffData = new LinkedHashMap<>();
        handoffData.put("reason", action.getReason());
        handoffData.put("summary", action.getSummary());
        handoffData.put("context", action.getContext());
        context.addTrace("handoff_requested", "Workflow 判断需要转人工", handoffData);

        // 只暂停、不建工单：真实闲鱼会话里“未授权承诺”需要的是卖家本人拍板，而不是
        // 一张人工工单。跳过能力网关（无副作用、无需幂等键），仍以 HANDED_OFF 收尾，
        // CustomerServiceAgent 会置 handoffRequested=true，侧车据此进入人工模式；
        // 卖家回复经 seller-message 端点写回会话后恢复机器人。
        if (action.isPauseOnly()) {
            Map<String, Object> pauseData = new LinkedHashMap<>();
            pauseData.put("reason", action.getReason());
            pauseData.put("summaryPreview", HandlerTraceSupport.preview(action.getSummary(), 160));
            context.addTrace("handoff_pause_only", "暂停机器人转卖家本人确认（不创建工单）", pauseData);
            String reply = action.getBuyerReply().isBlank()
                    ? "这件事我做不了主，已经帮您转告卖家本人，请稍等确认。"
                    : action.getBuyerReply();
            return ActionExecutionResult.handedOff(reply);
        }

        if (!skillManager.isCapabilityAllowed(skillPackage, HANDOFF_CAPABILITY)) {
            CapabilityObservation denied = new CapabilityObservation(
                    HANDOFF_CAPABILITY,
                    CapabilityOutcome.BUSINESS_REJECTED,
                    "当前 Skill 未授权创建人工转接工单",
                    null,
                    "CAPABILITY_NOT_ALLOWED",
                    "skill-allowlist",
                    false
            );
            observe(context, denied);

            Map<String, Object> deniedData = new LinkedHashMap<>();
            deniedData.put("capability", HANDOFF_CAPABILITY);
            deniedData.put("skillId", skillPackage.getDefinition().getId());
            context.addTrace("capability_denied", "Skill Package 未授权人工转接 Capability", deniedData);
            String answer = "非常抱歉，当前客服流程没有创建人工工单的权限，请联系系统管理员。";
            return ActionExecutionResult.completed(answer);
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        // userId 和 conversationId 属于服务端安全上下文，不能作为模型可控制的普通参数传递。
        // Local/MCP Provider 都会从 executionContext(...) 中读取可信身份与会话。
        arguments.put("reason", action.getReason());
        arguments.put("summary", action.getSummary());

        // 与 CapabilityActionHandler 统一 tool_called 词汇：评测只按 tool_called 统计能力触发；
        // 下面的 handoff_created/handoff_failed 仍保留给 MonitorService，它不统计 tool_called。
        context.addTrace("tool_called", "调用工具：" + HANDOFF_CAPABILITY,
                Map.of("capability", HANDOFF_CAPABILITY, "arguments", arguments));

        long startedAtNanos = System.nanoTime();
        CapabilityObservation result;
        try {
            CapabilityResult<?> gatewayResult = capabilityGateway.invoke(
                    new CapabilityCall(HANDOFF_CAPABILITY, arguments),
                    executionContext(handlingContext)
            );
            result = CapabilityObservation.from(gatewayResult);
        } catch (RuntimeException e) {
            Map<String, Object> failedData = new LinkedHashMap<>();
            failedData.put("durationMs", HandlerTraceSupport.elapsedMillis(startedAtNanos));
            failedData.put("error", String.valueOf(e.getMessage()));
            context.addTrace("handoff_failed", "人工转接工具调用异常", failedData);
            throw e;
        }

        observe(context, result);
        Map<String, Object> observedData = new LinkedHashMap<>();
        observedData.put("durationMs", HandlerTraceSupport.elapsedMillis(startedAtNanos));
        observedData.put("success", result.isSuccess());
        observedData.put("outcome", result.getOutcome().name());
        observedData.put("errorCode", result.getErrorCode());
        observedData.put("providerId", result.getProviderId());
        observedData.put("replayed", result.isReplayed());
        observedData.put("messagePreview", HandlerTraceSupport.preview(result.getMessage(), 200));

        if (result.isSuccess()) {
            observedData.put("ticketId", extractTicketId(result.getData()));
            context.addTrace("handoff_created", "已创建人工转接工单", observedData);
            String answer = buildHandoffAnswer(result, action.getReason());
            return ActionExecutionResult.handedOff(answer);
        }

        context.addTrace("handoff_failed", "人工转接工具返回失败", observedData);
        String answer = "非常抱歉，当前问题需要人工客服继续处理，但系统创建人工工单失败。请稍后重试，或换个说法再次请求人工客服。";
        return ActionExecutionResult.completed(answer);
    }

    @Override
    public Map<String, Object> describe(HandoffAction action) {
        Map<String, Object> data = ActionHandler.super.describe(action);
        data.put("reason", action.getReason());
        data.put("summaryPreview", HandlerTraceSupport.preview(action.getSummary(), 160));
        return data;
    }

    private void observe(TurnContext context, CapabilityObservation result) {
        context.addObservation("capability_result", result);
        context.getState().setTaskState("last_capability", HANDOFF_CAPABILITY);
        context.getState().setTaskState("last_capability_result", result);
    }

    private ExecutionContext executionContext(ActionHandlingContext handlingContext) {
        TurnContext turn = handlingContext.getTurnContext();
        String conversationId = turn.getRequest().getConversationId();
        String conversationScope = ConversationKey.from(turn.getRequest()).toScopedId();
        String workflowId = handlingContext.getWorkflow().descriptor().getId();
        // 人工转接也是副作用能力。它既不能突破 Handler 的 30 秒上限，也不能挤占下一轮时间。
        long upstreamTimeoutMs = Math.min(
                DEFAULT_UPSTREAM_TIMEOUT_MS,
                turn.remainingTurnBudgetMs()
        );
        return new ExecutionContext(
                conversationScope + ":step-" + turn.getStepCount(),
                turn.getRequest().getPrincipal(),
                turn.getRequest().getTenantId(),
                conversationId,
                conversationScope + ":" + workflowId
                        + ":" + turn.getState().getWorkflowStartedAt() + ":handoff",
                Instant.now().plus(upstreamTimeoutMs, ChronoUnit.MILLIS)
        );
    }

    private String buildHandoffAnswer(CapabilityObservation result, String reason) {
        String ticketId = extractTicketId(result.getData());
        if (ticketId == null) {
            ticketId = "人工工单已创建";
        }
        if (HandoffAction.REASON_PRODUCT_VERIFICATION.equals(reason)) {
            // 商品核实不是投诉或人工接管，不能用“带来不好的体验/转接人工客服”的
            // 道歉口径；买家预期是“等卖家实测结果”。
            return "您问的这个功能我这边还没有确切结论，已经请卖家本人实际核实（记录编号："
                    + ticketId + "），结果确认后马上告诉您，请稍等。";
        }
        return "非常抱歉给您带来不好的体验。当前问题我已为您转接人工客服，工单号：" + ticketId
                + "。人工客服会参考当前会话摘要继续处理，请您稍等。";
    }

    private String extractTicketId(Object data) {
        HandoffTicketSummary ticket = HandoffTicketSummary.fromMap(data);
        return ticket == null ? null : ticket.getTicketId();
    }
}
