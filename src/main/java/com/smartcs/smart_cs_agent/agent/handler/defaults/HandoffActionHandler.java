package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.tools.HandoffTicketData;
import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** 创建人工工单并结束自动 Workflow 的 Action 插件。 */
@Component
public class HandoffActionHandler implements ActionHandler<HandoffAction> {

    private static final String HANDOFF_CAPABILITY = CapabilityIds.HANDOFF_REQUEST;

    private final ToolManager toolManager;
    private final SkillManager skillManager;

    public HandoffActionHandler(ToolManager toolManager, SkillManager skillManager) {
        this.toolManager = toolManager;
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

        if (!skillManager.isCapabilityAllowed(skillPackage, HANDOFF_CAPABILITY)) {
            ToolResult denied = ToolResult.failure(
                    HANDOFF_CAPABILITY,
                    "CAPABILITY_NOT_ALLOWED",
                    "当前 Skill 未授权创建人工转接工单"
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
        arguments.put("user_id", context.getRequest().getUserId());
        arguments.put("conversation_id", context.getRequest().getConversationId());
        arguments.put("reason", action.getReason());
        arguments.put("summary", action.getSummary());

        long startedAtNanos = System.nanoTime();
        ToolResult result;
        try {
            result = toolManager.execute(
                    new ToolCall(HANDOFF_CAPABILITY, arguments),
                    executionContext(handlingContext)
            );
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
            String answer = buildHandoffAnswer(result);
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

    private void observe(TurnContext context, ToolResult result) {
        context.addObservation("capability", result);
        context.getState().setTaskState("last_capability", HANDOFF_CAPABILITY);
        context.getState().setTaskState("last_capability_result", result);
    }

    private ExecutionContext executionContext(ActionHandlingContext handlingContext) {
        TurnContext turn = handlingContext.getTurnContext();
        String conversationId = turn.getRequest().getConversationId();
        String workflowId = handlingContext.getWorkflow().descriptor().getId();
        return new ExecutionContext(
                conversationId + ":step-" + turn.getStepCount(),
                turn.getRequest().getPrincipal(),
                "default",
                conversationId,
                conversationId + ":" + workflowId + ":" + turn.getState().getWorkflowStartedAt() + ":handoff",
                Instant.now().plus(30, ChronoUnit.SECONDS)
        );
    }

    private String buildHandoffAnswer(ToolResult result) {
        String ticketId = extractTicketId(result.getData());
        if (ticketId == null) {
            ticketId = "人工工单已创建";
        }
        return "非常抱歉给您带来不好的体验。当前问题我已为您转接人工客服，工单号：" + ticketId
                + "。人工客服会参考当前会话摘要继续处理，请您稍等。";
    }

    private String extractTicketId(Object data) {
        if (data instanceof HandoffTicketData ticket) {
            return ticket.getTicketId();
        }
        if (data instanceof Map<?, ?> ticket) {
            Object ticketId = ticket.get("ticket_id");
            return ticketId == null ? null : ticketId.toString();
        }
        return null;
    }
}
