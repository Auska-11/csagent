package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import com.smartcs.smart_cs_agent.agent.llm.LlmExecutionGuard;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.ResolvedSkillPrompt;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLMAction 执行插件：组装 Prompt、调用模型、记录 Observation，并在模型故障时降级。
 */
@Component
public class LlmActionHandler implements ActionHandler<LLMAction> {

    private final SkillManager skillManager;
    private final ChatLanguageModel chatModel;
    private final LlmExecutionGuard executionGuard;

    /** 兼容不启动 Spring 的旧单元测试；生产环境使用下面的三参数构造函数。 */
    public LlmActionHandler(SkillManager skillManager, ChatLanguageModel chatModel) {
        this(skillManager, chatModel, LlmExecutionGuard.sharedDefault());
    }

    @Autowired
    public LlmActionHandler(SkillManager skillManager,
                            ChatLanguageModel chatModel,
                            LlmExecutionGuard executionGuard) {
        this.skillManager = skillManager;
        this.chatModel = chatModel;
        this.executionGuard = executionGuard;
    }

    @Override
    public Class<LLMAction> actionType() {
        return LLMAction.class;
    }

    @Override
    public ActionExecutionResult handle(LLMAction action, ActionHandlingContext handlingContext) {
        TurnContext context = handlingContext.getTurnContext();
        ConversationState state = context.getState();
        String purpose = action.getPurpose();
        String prompt = action.getPrompt();
        state.setTaskState("last_llm_purpose", purpose);

        List<ChatMessage> messages = new ArrayList<>();
        ResolvedSkillPrompt resolvedPrompt = skillManager.resolveSystemPrompt(
                handlingContext.getSkillPackage(),
                purpose,
                context.getMessage() + "\n" + prompt
        );
        messages.add(SystemMessage.from(resolvedPrompt.getContent()));

        Map<String, Object> referenceData = new LinkedHashMap<>();
        referenceData.put("skillId", resolvedPrompt.getSkillId());
        referenceData.put("skillVersion", resolvedPrompt.getSkillVersion());
        referenceData.put("purpose", purpose);
        referenceData.put("references", resolvedPrompt.getLoadedReferences());
        referenceData.put("referenceCount", resolvedPrompt.getLoadedReferences().size());
        context.addTrace("skill_references_loaded", "按当前 LLM 用途加载 Skill 参考资料", referenceData);

        if (state.hasSummaryMemory()) {
            messages.add(SystemMessage.from(
                    "以下是较早会话的压缩摘要，请作为上下文参考，不要逐字复述给用户：\n"
                            + state.getSummaryMemory()
            ));
        }

        for (Map<String, String> message : state.getMessages()) {
            if ("user".equals(message.get("role"))) {
                messages.add(UserMessage.from(message.get("content")));
            } else if ("assistant".equals(message.get("role"))) {
                messages.add(AiMessage.from(message.get("content")));
            }
        }
        messages.add(UserMessage.from(prompt));

        Map<String, Object> callData = new LinkedHashMap<>();
        callData.put("purpose", purpose);
        callData.put("promptPreview", HandlerTraceSupport.preview(prompt, 160));
        callData.put("messageCount", messages.size());
        context.addTrace("llm_called", "调用大模型：" + purpose, callData);

        LlmExecutionGuard.LlmInvocationResult invocation = executionGuard.generate(
                chatModel,
                messages,
                context
        );
        String result = invocation.text();
        if (!invocation.success()) {
            Map<String, Object> failedData = new LinkedHashMap<>();
            failedData.put("purpose", purpose);
            failedData.put("durationMs", invocation.durationMs());
            failedData.put("budgetMs", invocation.budgetMs());
            failedData.put("errorCode", invocation.errorCode());
            context.addTrace("llm_failed", "大模型调用失败，使用降级回复", failedData);
            result = buildFallbackResult(action, context);
        }

        context.addObservation("llm_response", result);
        state.setTaskState(purpose + "_result", result);

        Map<String, Object> observedData = new LinkedHashMap<>();
        observedData.put("purpose", purpose);
        observedData.put("durationMs", invocation.durationMs());
        observedData.put("budgetMs", invocation.budgetMs());
        observedData.put("degraded", !invocation.success());
        observedData.put("resultPreview", HandlerTraceSupport.preview(result, 200));
        context.addTrace("llm_observed", "大模型返回结果", observedData);

        // InterruptedException 表示上游请求线程已经被要求停止。此时绝不能像普通模型故障一样
        // 返回 CONTINUE，否则 Workflow 可能继续启动下一个 Capability，甚至产生副作用。
        if ("LLM_EXECUTION_INTERRUPTED".equals(invocation.errorCode())) {
            return ActionExecutionResult.failed(
                    "当前处理已被中断，为避免继续执行后续操作已经安全停止。请重新发起请求。"
            );
        }
        return ActionExecutionResult.continueLoop();
    }

    @Override
    public Map<String, Object> describe(LLMAction action) {
        return Map.of(
                "actionType", actionType().getSimpleName(),
                "purpose", action.getPurpose(),
                "promptPreview", HandlerTraceSupport.preview(action.getPrompt(), 160)
        );
    }

    private String buildFallbackResult(LLMAction action, TurnContext context) {
        String purpose = action.getPurpose();
        if ("intent_recognition".equals(purpose)) {
            return "UNKNOWN";
        }

        if ("response_generation".equals(purpose)) {
            Object lastCapabilityResult = context.getState().getTaskState("last_capability_result");
            if (lastCapabilityResult instanceof CapabilityObservation result) {
                return buildCapabilityFallbackAnswer(result);
            }
            return "您好，我是智能客服。当前大模型服务暂时不可用，但我仍可以帮您查询订单、商品、退货进度或转人工客服。";
        }
        return "当前大模型服务暂时不可用，请稍后再试。";
    }

    private String buildCapabilityFallbackAnswer(CapabilityObservation result) {
        if (!result.isSuccess()) {
            return "抱歉，当前工具查询失败：" + result.getMessage()
                    + "。请核对信息后再试，或发送“我要找人工客服”。";
        }

        String message = result.getMessage() == null || result.getMessage().isBlank()
                ? "工具已成功返回结果。"
                : result.getMessage();
        return switch (result.getCapabilityId()) {
            case "order.lookup" -> "我已为您查询到订单信息：\n" + message;
            case "order.list-by-user" -> "我已为您查询到当前用户的订单列表：\n" + message;
            case "product.search", "product.lookup" -> "我已为您查询到商品信息：\n" + message;
            case "return.apply" -> "退货申请处理结果如下：\n" + message;
            case "return.lookup" -> "我已为您查询到退货进度：\n" + message;
            case "user.lookup" -> "我已为您查询到用户信息：\n" + message;
            case "knowledge.search" -> "我根据客服知识库查到以下信息：\n" + message;
            default -> "我已处理完成：\n" + message;
        };
    }
}
