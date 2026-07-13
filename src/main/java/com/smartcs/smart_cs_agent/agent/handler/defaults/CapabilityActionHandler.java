package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行 CapabilityAction 的默认插件。
 *
 * <p>所有普通能力调用统一经过：Skill allowlist -> ExecutionContext -> ToolManager/Gateway -> Observation。
 * Workflow 不接触 Provider，本 Handler 也不包含订单、商品等具体领域判断。</p>
 */
@Component
public class CapabilityActionHandler implements ActionHandler<CapabilityAction> {

    private final ToolManager toolManager;
    private final SkillManager skillManager;

    public CapabilityActionHandler(ToolManager toolManager, SkillManager skillManager) {
        this.toolManager = toolManager;
        this.skillManager = skillManager;
    }

    @Override
    public Class<CapabilityAction> actionType() {
        return CapabilityAction.class;
    }

    @Override
    public ActionExecutionResult handle(CapabilityAction action, ActionHandlingContext handlingContext) {
        TurnContext context = handlingContext.getTurnContext();
        ConversationState state = context.getState();
        AgentSkillPackage skillPackage = handlingContext.getSkillPackage();
        String capability = action.getCapability();
        // Legacy Workflow 仍可能发出 order_lookup 这样的旧名称。
        // 新 Observation 一律保存 canonical ID，使新 Workflow 不需要认识迁移期别名。
        String canonicalCapabilityId = CapabilityIds.canonicalize(capability);
        Map<String, Object> arguments = action.getArguments();
        long startedAtNanos = System.nanoTime();
        ToolResult result;

        // Provider 中“存在”某项能力，不等于当前 Skill 被“授权”调用它。
        if (!skillManager.isCapabilityAllowed(skillPackage, capability)) {
            Map<String, Object> deniedData = new LinkedHashMap<>();
            deniedData.put("capability", capability);
            deniedData.put("skillId", skillPackage.getDefinition().getId());
            deniedData.put("allowedCapabilities", skillPackage.getDefinition().getAllowedCapabilities());
            context.addTrace("capability_denied", "Skill Package 未授权此 Capability", deniedData);
            result = ToolResult.failure(
                    capability,
                    "CAPABILITY_NOT_ALLOWED",
                    "当前 Skill 未授权调用 Capability: " + capability
            );
        } else {
            context.addTrace("tool_called", "调用工具：" + capability, Map.of(
                    "capability", capability,
                    "arguments", arguments
            ));

            try {
                result = toolManager.execute(
                        new ToolCall(capability, arguments),
                        buildExecutionContext(handlingContext, capability)
                );
            } catch (RuntimeException e) {
                Map<String, Object> failedData = new LinkedHashMap<>();
                failedData.put("capability", capability);
                failedData.put("durationMs", HandlerTraceSupport.elapsedMillis(startedAtNanos));
                failedData.put("error", String.valueOf(e.getMessage()));
                context.addTrace("tool_failed", "工具调用失败：" + capability, failedData);
                throw e;
            }
        }

        long durationMs = HandlerTraceSupport.elapsedMillis(startedAtNanos);
        // 保留 capability + ToolResult，保证尚未迁移的 CustomerServiceSkill 继续工作。
        context.addObservation("capability", result);

        // 新 Workflow 只读取 capability_result。这里把 Provider 差异压平，
        // 因此从 local 切到 mcp-remote 时不需要修改 Workflow。
        context.addObservation("capability_result", new CapabilityObservation(
                canonicalCapabilityId,
                result.getOutcome(),
                result.getMessage(),
                result.getData(),
                result.getErrorCode(),
                result.getProviderId(),
                result.isReplayed()
        ));
        state.setTaskState("last_capability", capability);
        state.setTaskState("last_capability_result", result);

        Map<String, Object> observedData = new LinkedHashMap<>();
        observedData.put("capability", capability);
        observedData.put("durationMs", durationMs);
        observedData.put("success", result.isSuccess());
        observedData.put("outcome", result.getOutcome().name());
        observedData.put("errorCode", result.getErrorCode());
        observedData.put("providerId", result.getProviderId());
        observedData.put("replayed", result.isReplayed());
        observedData.put("messagePreview", HandlerTraceSupport.preview(result.getMessage(), 200));
        context.addTrace("tool_observed", "工具返回结果：" + capability, observedData);

        if (CapabilityIds.KNOWLEDGE_SEARCH.equals(canonicalCapabilityId)
                && isEmptyKnowledgeResult(result.getData())) {
            context.addTrace("knowledge_miss", "知识库没有可靠命中", Map.of(
                    "capability", capability,
                    "arguments", arguments,
                    "messagePreview", HandlerTraceSupport.preview(result.getMessage(), 200)
            ));
        }
        return ActionExecutionResult.continueLoop();
    }

    @Override
    public Map<String, Object> describe(CapabilityAction action) {
        return Map.of(
                "actionType", actionType().getSimpleName(),
                "capability", action.getCapability(),
                "arguments", action.getArguments()
        );
    }

    /**
     * 安全字段来自服务端 TurnContext，而不是 LLM 可生成的 arguments。
     * Chat API 会先通过 Spring Security 验证 JWT 并生成 Principal；副作用能力还会由 Gateway
     * 再次检查 isAuthenticated，未验证 claim 无法越过这道边界。
     */
    private ExecutionContext buildExecutionContext(ActionHandlingContext context, String capability) {
        TurnContext turn = context.getTurnContext();
        String conversationId = turn.getRequest().getConversationId();
        String conversationScope = ConversationKey.from(turn.getRequest()).toScopedId();
        String workflowId = context.getWorkflow().descriptor().getId();
        String traceId = conversationScope + ":step-" + turn.getStepCount();

        // 同一 Workflow 会话对同一副作用能力生成稳定 key。
        // Gateway 会用该 key 合并并发请求并回放成功结果，避免副作用重复执行。
        String idempotencyKey = conversationScope
                + ":" + workflowId
                + ":" + turn.getState().getWorkflowStartedAt()
                + ":" + capability;

        // 这是普通 Action 给出的上游最大预算。Gateway 还会读取 CapabilityDefinition.defaultTimeoutMs，
        // 两者取较早值，所以某个默认 2 秒的能力不会因为这里写 30 秒就多执行 28 秒。
        return new ExecutionContext(
                traceId,
                turn.getRequest().getPrincipal(),
                turn.getRequest().getTenantId(),
                conversationId,
                idempotencyKey,
                Instant.now().plus(30, ChronoUnit.SECONDS)
        );
    }

    private boolean isEmptyKnowledgeResult(Object data) {
        if (data == null) {
            return true;
        }
        return data instanceof List<?> results && results.isEmpty();
    }
}
