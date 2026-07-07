package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.*;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.skill.Skill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.tools.HandoffTicketData;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行引擎
 *
 * 【核心职责】
 * AgentRuntime 是 Agent 的执行引擎，负责循环执行 Action。
 * 它不关心"做什么"（由 Skill 决定），只关心"怎么做"（执行 Action）。
 *
 * 【执行流程】
 * 1. 接收 TurnContext（当前轮执行上下文）
 * 2. 循环调用 Skill.nextAction() 获取下一步操作
 * 3. 根据 Action 类型执行对应逻辑
 * 4. 更新 TurnContext 和 ConversationState
 * 5. 遇到 FinalAnswerAction 或 AskUserAction 时停止循环
 *
 * 【与 Skill 的分工】
 * - Skill：决策（我应该做什么？）→ 返回 Action
 * - AgentRuntime：执行（我来做！）→ 执行 Action
 *
 * 【与项目 1 的对比】
 * 项目 1 的 AgentRuntime 也是执行循环，我们参考它的设计。
 * 核心思想：Skill 负责决策，Runtime 负责执行，职责分离。
 */
@Component
public class AgentRuntime {

    /** Skill 管理器：获取当前活跃的 Skill */
    private final SkillManager skillManager;

    /** LLM 模型：调用大语言模型 */
    private final ChatLanguageModel chatModel;

    /** 工具管理器：调用业务工具（订单、产品、退换货等） */
    private final ToolManager toolManager;

    /**
     * 构造函数注入
     *
     * Spring 会自动注入这三个依赖，不需要手动创建。
     */
    public AgentRuntime(SkillManager skillManager, ChatLanguageModel chatModel, ToolManager toolManager) {
        this.skillManager = skillManager;
        this.chatModel = chatModel;
        this.toolManager = toolManager;
    }

    /**
     * 执行一轮对话
     *
     * 【这是 AgentRuntime 的核心方法】
     * 接收 ConversationState，循环执行 Action，直到得到最终回复。
     *
     * 这是兼容旧代码的入口。
     * 新代码优先调用 runTurn(TurnContext context)，因为 TurnContext 能同时携带：
     * 当前请求、跨轮状态、本轮观察结果。
     *
     * @param state 会话状态
     * @return 最终回复或追问问题
     */
    public String runTurn(ConversationState state) {
        ChatRequest request = new ChatRequest("legacy-" + state.getUserId(), state.getUserId(), state.getMessage());
        return runTurn(new TurnContext(request, state));
    }

    /**
     * 执行一轮对话
     *
     * 【这是 AgentRuntime 的核心方法】
     * 接收 TurnContext，循环执行 Action，直到得到最终回复。
     *
     * 【执行流程】
     * 1. 获取当前 Skill
     * 2. 循环调用 Skill.nextAction() 获取 Action
     * 3. 根据 Action 类型执行：
     *    - FinalAnswerAction → 返回最终回复，结束循环
     *    - AskUserAction → 返回追问问题，结束循环
     *    - LLMAction → 调用 LLM，继续循环
     *    - CapabilityAction → 调用工具，继续循环
     *
     * @param context 当前这一轮执行上下文
     * @return 最终回复或追问问题
     */
    public String runTurn(TurnContext context) {
        ConversationState state = context.getState();

        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("conversationId", context.getRequest().getConversationId());
        startData.put("userId", context.getRequest().getUserId());
        startData.put("message", context.getMessage());
        context.addTrace("turn_started", "开始处理用户本轮消息", startData);
        context.addTrace("memory_loaded", "读取会话记忆", buildMemoryTraceData(state));

        // 1. 获取当前活跃的 Skill
        Skill skill = skillManager.getActiveSkill();
        context.addTrace("skill_selected", "选择当前客服 Skill", Map.of("skill", skill.getName()));

        // 2. 循环执行（最多 10 次，防止死循环）
        int maxIterations = 10;
        for (int i = 0; i < maxIterations; i++) {
            context.increaseStepCount();

            // 3. Skill 决定下一步操作
            AgentAction action = skill.nextAction(context);
            context.addTrace("action_planned", "Skill 决定下一步 Action", buildActionTraceData(action));

            // 4. 根据 Action 类型执行

            // 如果是最终回复，停止循环，返回结果
            if (action instanceof FinalAnswerAction) {
                String answer = ((FinalAnswerAction) action).getAnswer();
                context.addTrace("final_answer", "生成最终回复，结束本轮对话", Map.of("answer", answer));
                int summaryVersionBefore = state.getSummaryVersion();
                state.addAssistantMessage(answer);  // 保存到历史记录
                recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);

                // 最终回复已经返回，说明当前任务闭环完成。
                // 这时清理订单号、退货原因等临时槽位，避免影响下一次新任务。
                state.clearTaskProgress();
                context.markDone(answer);
                return answer;
            }

            // 如果是追问用户，停止循环，返回问题
            if (action instanceof AskUserAction) {
                String question = ((AskUserAction) action).getQuestion();
                context.addTrace("ask_user", "信息不足，向用户追问", Map.of("question", question));

                // 追问也是 Agent 对用户说的话，也应该写入历史记录。
                //
                // 如果不保存这句话，下一轮只看到用户回复“ORD-004”，
                // 但历史里没有“请提供订单号”这个上下文，后续做 LLM 总结或 Trace 时会不完整。
                int summaryVersionBefore = state.getSummaryVersion();
                state.addAssistantMessage(question);
                recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);
                context.markDone(question);
                return question;
            }

            // 如果需要转人工，创建人工工单并结束本轮。
            if (action instanceof HandoffAction) {
                String answer = executeHandoffAction((HandoffAction) action, context);
                context.addTrace("final_answer", "已转人工，结束本轮对话", Map.of("answer", answer));
                int summaryVersionBefore = state.getSummaryVersion();
                state.addAssistantMessage(answer);
                recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);
                state.clearTaskProgress();
                context.markDone(answer);
                return answer;
            }

            // 如果是 LLM 调用，执行后继续循环
            if (action instanceof LLMAction) {
                LLMAction llmAction = (LLMAction) action;
                // 保存 purpose 到状态，供下次循环判断
                state.setTaskState("last_llm_purpose", llmAction.getPurpose());
                executeLLMAction(llmAction, context);
            }

            // 如果是工具调用，执行后继续循环
            if (action instanceof CapabilityAction) {
                executeCapabilityAction((CapabilityAction) action, context);
            }
        }

        // 超过最大循环次数，返回超时提示
        String timeoutAnswer = "当前处理步骤过多，已暂停。请换个说法再试一次。";
        context.addTrace("turn_timeout", "本轮执行超过最大步数", Map.of("maxIterations", maxIterations));
        int summaryVersionBefore = state.getSummaryVersion();
        state.addAssistantMessage(timeoutAnswer);
        recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);
        context.markDone(timeoutAnswer);
        return timeoutAnswer;
    }

    /**
     * 执行 LLM 调用
     *
     * 【执行流程】
     * 1. 构建消息列表（System Prompt + 历史对话 + 当前 Prompt）
     * 2. 调用 LLM 生成回复
     * 3. 保存结果到 TurnContext，同时过渡性同步到 ConversationState
     *
     * @param action LLMAction（包含 purpose 和 prompt）
     * @param context 当前这一轮执行上下文
     */
    private void executeLLMAction(LLMAction action, TurnContext context) {
        ConversationState state = context.getState();
        String purpose = action.getPurpose();  // 用途：intent_recognition 或 response_generation
        String prompt = action.getPrompt();    // 提示词

        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();

        // 添加 System Prompt（从 SkillManager 获取）
        String systemPrompt = skillManager.getSystemPrompt();
        messages.add(SystemMessage.from(systemPrompt));

        // 添加摘要记忆。
        //
        // 短期消息窗口只保留最近几条消息，较早的内容会进入 summaryMemory。
        // 如果这里不把摘要补给 LLM，长对话压缩后模型就会“忘记”较早上下文。
        if (state.hasSummaryMemory()) {
            messages.add(SystemMessage.from("以下是较早会话的压缩摘要，请作为上下文参考，不要逐字复述给用户：\n"
                    + state.getSummaryMemory()));
        }

        // 添加历史对话
        for (Map<String, String> msg : state.getMessages()) {
            if ("user".equals(msg.get("role"))) {
                messages.add(UserMessage.from(msg.get("content")));
            } else if ("assistant".equals(msg.get("role"))) {
                messages.add(AiMessage.from(msg.get("content")));
            }
        }

        // 添加当前 Prompt
        messages.add(UserMessage.from(prompt));

        Map<String, Object> callData = new LinkedHashMap<>();
        callData.put("purpose", purpose);
        callData.put("promptPreview", preview(prompt, 160));
        callData.put("messageCount", messages.size());
        context.addTrace("llm_called", "调用大模型：" + purpose, callData);

        // 调用 LLM，并记录耗时。
        // 以后做 Monitor 或性能分析时，可以直接从 Trace 中统计平均 LLM 耗时。
        long startedAtNanos = System.nanoTime();
        String result;
        try {
            Response<AiMessage> response = chatModel.generate(messages);
            result = response.content().text();
        } catch (RuntimeException e) {
            Map<String, Object> failedData = new LinkedHashMap<>();
            failedData.put("purpose", purpose);
            failedData.put("durationMs", elapsedMillis(startedAtNanos));
            failedData.put("error", String.valueOf(e.getMessage()));
            context.addTrace("llm_failed", "大模型调用失败，使用降级回复", failedData);

            // 面试项目里最好不要让“模型暂时不可用”直接打断整条客服链路。
            // 如果前面的工具已经查到了结构化结果，就用工具结果拼一个基础客服回复；
            // 这样既能保留可演示性，也能体现 Agent 的降级设计。
            result = buildLlmFallbackResult(action, context);
        }
        long durationMs = elapsedMillis(startedAtNanos);

        // 保存结果到状态
        context.addObservation("llm_response", result);  // 添加到本轮观察结果
        state.setTaskState(purpose + "_result", result);  // 保存到任务状态

        Map<String, Object> observedData = new LinkedHashMap<>();
        observedData.put("purpose", purpose);
        observedData.put("durationMs", durationMs);
        observedData.put("resultPreview", preview(result, 200));
        context.addTrace("llm_observed", "大模型返回结果", observedData);
    }

    /**
     * LLM 调用失败时的降级结果。
     *
     * 这里不追求替代大模型的表达能力，只保证：
     * 1. 接口不要因为模型失败直接 500；
     * 2. 用户能看到工具已经查到的核心信息；
     * 3. Trace 里能清楚看到本轮发生过降级。
     */
    private String buildLlmFallbackResult(LLMAction action, TurnContext context) {
        String purpose = action.getPurpose();

        if ("intent_recognition".equals(purpose)) {
            return "UNKNOWN";
        }

        if ("response_generation".equals(purpose)) {
            Object lastCapabilityResult = context.getState().getTaskState("last_capability_result");
            if (lastCapabilityResult instanceof ToolResult result) {
                return buildToolResultFallbackAnswer(result);
            }

            return "您好，我是智能客服。当前大模型服务暂时不可用，但我仍可以帮您查询订单、商品、退货进度或转人工客服。";
        }

        return "当前大模型服务暂时不可用，请稍后再试。";
    }

    /**
     * 根据工具结果生成一个模板化回复。
     *
     * 正常情况下，工具结果会交给 LLM 润色；
     * 当 LLM 不可用时，这个方法负责把 ToolResult.message 直接整理成用户能读的中文提示。
     */
    private String buildToolResultFallbackAnswer(ToolResult result) {
        if (!result.isSuccess()) {
            return "抱歉，当前工具查询失败：" + result.getMessage()
                    + "。请核对信息后再试，或发送“我要找人工客服”。";
        }

        String message = result.getMessage() == null || result.getMessage().isBlank()
                ? "工具已成功返回结果。"
                : result.getMessage();

        return switch (result.getToolName()) {
            case "order_lookup" -> "我已为您查询到订单信息：\n" + message;
            case "user_orders" -> "我已为您查询到当前用户的订单列表：\n" + message;
            case "product_search", "product_info" -> "我已为您查询到商品信息：\n" + message;
            case "return_apply" -> "退货申请处理结果如下：\n" + message;
            case "return_query" -> "我已为您查询到退货进度：\n" + message;
            case "user_lookup" -> "我已为您查询到用户信息：\n" + message;
            case "knowledge_search" -> "我根据客服知识库查到以下信息：\n" + message;
            default -> "我已处理完成：\n" + message;
        };
    }

    /**
     * 执行工具调用
     *
     * 【执行流程】
     * 1. 从 Action 获取工具名称和参数
     * 2. 调用 ToolManager 执行工具
     * 3. 保存结果到 TurnContext，同时过渡性同步到 ConversationState
     *
     * @param action CapabilityAction（包含 capability 和 arguments）
     * @param context 当前这一轮执行上下文
     */
    private void executeCapabilityAction(CapabilityAction action, TurnContext context) {
        ConversationState state = context.getState();
        String capability = action.getCapability();  // 工具名称：order_lookup, product_search 等
        Map<String, Object> arguments = action.getArguments();  // 参数：order_id, keyword 等

        Map<String, Object> callData = new LinkedHashMap<>();
        callData.put("capability", capability);
        callData.put("arguments", arguments);
        context.addTrace("tool_called", "调用工具：" + capability, callData);

        // 调用工具管理器执行工具，并记录耗时。
        // 这个 durationMs 后续可以用于发现慢工具、统计平均工具耗时。
        long startedAtNanos = System.nanoTime();
        ToolResult result;
        try {
            result = toolManager.execute(capability, arguments);
        } catch (RuntimeException e) {
            Map<String, Object> failedData = new LinkedHashMap<>();
            failedData.put("capability", capability);
            failedData.put("durationMs", elapsedMillis(startedAtNanos));
            failedData.put("error", String.valueOf(e.getMessage()));
            context.addTrace("tool_failed", "工具调用失败：" + capability, failedData);
            throw e;
        }
        long durationMs = elapsedMillis(startedAtNanos);

        // 保存结果到状态
        context.addObservation("capability", result);  // 添加到本轮观察结果
        state.setTaskState("last_capability", capability);  // 记录最后调用的工具
        state.setTaskState("last_capability_result", result);  // 保存工具结果

        Map<String, Object> observedData = new LinkedHashMap<>();
        observedData.put("capability", capability);
        observedData.put("durationMs", durationMs);
        observedData.put("success", result.isSuccess());
        observedData.put("errorCode", result.getErrorCode());
        observedData.put("messagePreview", preview(result.getMessage(), 200));
        context.addTrace("tool_observed", "工具返回结果：" + capability, observedData);

        // knowledge_search 执行成功但没有命中文档时，不应该算工具异常；
        // 它更准确地表示“知识库召回失败/知识缺口”。
        // 单独记录 knowledge_miss，后续做 Monitor 或 RAG 评测时就能发现召回不全的问题。
        if ("knowledge_search".equals(capability) && isEmptyKnowledgeResult(result.getData())) {
            Map<String, Object> missData = new LinkedHashMap<>();
            missData.put("capability", capability);
            missData.put("arguments", arguments);
            missData.put("messagePreview", preview(result.getMessage(), 200));
            context.addTrace("knowledge_miss", "知识库没有可靠命中", missData);
        }
    }

    /**
     * 执行人工转接。
     *
     * 【为什么 Runtime 来执行？】
     * Skill 只负责判断“应该转人工”；
     * Runtime 负责真正调用转人工工具、记录 Trace、生成最终提示。
     *
     * 这和普通工具调用的思想一致：决策和执行分开。
     */
    private String executeHandoffAction(HandoffAction action, TurnContext context) {
        ConversationState state = context.getState();

        Map<String, Object> handoffData = new LinkedHashMap<>();
        handoffData.put("reason", action.getReason());
        handoffData.put("summary", action.getSummary());
        handoffData.put("context", action.getContext());
        context.addTrace("handoff_requested", "Skill 判断需要转人工", handoffData);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("user_id", context.getRequest().getUserId());
        arguments.put("conversation_id", context.getRequest().getConversationId());
        arguments.put("reason", action.getReason());
        arguments.put("summary", action.getSummary());

        long startedAtNanos = System.nanoTime();
        ToolResult result;
        try {
            result = toolManager.execute("handoff_create", arguments);
        } catch (RuntimeException e) {
            Map<String, Object> failedData = new LinkedHashMap<>();
            failedData.put("durationMs", elapsedMillis(startedAtNanos));
            failedData.put("error", String.valueOf(e.getMessage()));
            context.addTrace("handoff_failed", "人工转接工具调用异常", failedData);
            throw e;
        }
        long durationMs = elapsedMillis(startedAtNanos);

        context.addObservation("capability", result);
        state.setTaskState("last_capability", "handoff_create");
        state.setTaskState("last_capability_result", result);

        Map<String, Object> observedData = new LinkedHashMap<>();
        observedData.put("durationMs", durationMs);
        observedData.put("success", result.isSuccess());
        observedData.put("errorCode", result.getErrorCode());
        observedData.put("messagePreview", preview(result.getMessage(), 200));

        if (result.isSuccess()) {
            observedData.put("ticketId", extractTicketId(result.getData()));
            context.addTrace("handoff_created", "已创建人工转接工单", observedData);
            return buildHandoffAnswer(result);
        }

        context.addTrace("handoff_failed", "人工转接工具返回失败", observedData);
        return "非常抱歉，当前问题需要人工客服继续处理，但系统创建人工工单失败。请稍后重试，或换个说法再次请求人工客服。";
    }

    /**
     * 把 Action 转成适合 Trace 展示的结构化数据。
     *
     * 注意：这里不执行 Action，只是记录“计划要做什么”。
     */
    private Map<String, Object> buildActionTraceData(AgentAction action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionType", action.getClass().getSimpleName());

        if (action instanceof CapabilityAction) {
            CapabilityAction capabilityAction = (CapabilityAction) action;
            data.put("capability", capabilityAction.getCapability());
            data.put("arguments", capabilityAction.getArguments());
        }

        if (action instanceof LLMAction) {
            LLMAction llmAction = (LLMAction) action;
            data.put("purpose", llmAction.getPurpose());
            data.put("promptPreview", preview(llmAction.getPrompt(), 160));
        }

        if (action instanceof AskUserAction) {
            data.put("question", ((AskUserAction) action).getQuestion());
        }

        if (action instanceof HandoffAction) {
            HandoffAction handoffAction = (HandoffAction) action;
            data.put("reason", handoffAction.getReason());
            data.put("summaryPreview", preview(handoffAction.getSummary(), 160));
        }

        if (action instanceof FinalAnswerAction) {
            data.put("answerPreview", preview(((FinalAnswerAction) action).getAnswer(), 160));
        }

        return data;
    }

    /**
     * 构造记忆相关 Trace 数据。
     *
     * 这里不把完整消息内容放进 Trace，避免 Trace 太长；
     * 前端 Memory 面板如果需要完整短期消息，会调用专门的 memory 接口。
     */
    private Map<String, Object> buildMemoryTraceData(ConversationState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeIntent", state.getActiveIntent());
        data.put("pendingSlot", state.getPendingSlot());
        data.put("shortTermMessageCount", state.getMessages().size());
        data.put("totalMessageCount", state.getTotalMessageCount());
        data.put("summaryVersion", state.getSummaryVersion());
        data.put("hasSummary", state.hasSummaryMemory());
        return data;
    }

    /**
     * 如果本轮新增助手消息后触发了摘要压缩，就把这件事记录进 Trace。
     */
    private void recordMemoryCompressionIfNeeded(TurnContext context,
                                                 int summaryVersionBefore,
                                                 ConversationState state) {
        if (state.getSummaryVersion() <= summaryVersionBefore) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summaryVersion", state.getSummaryVersion());
        data.put("summaryUpdatedAt", state.getSummaryUpdatedAt());
        data.put("shortTermMessageCount", state.getMessages().size());
        data.put("summaryPreview", preview(state.getSummaryMemory(), 180));
        context.addTrace("memory_compressed", "长会话已压缩为摘要记忆", data);
    }

    /**
     * 生成调试预览文本，避免 Trace 里塞入过长内容。
     */
    private String preview(Object value, int maxLength) {
        if (value == null) {
            return null;
        }

        String text = value.toString();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
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
        // 新版 handoff_create 工具返回的是 DTO 对象，优先从 DTO 中读取工单号。
        if (data instanceof HandoffTicketData ticket) {
            return ticket.getTicketId();
        }

        // 保留 Map 兼容逻辑，方便后续接入真实 MCP/HTTP 工具时复用。
        if (data instanceof Map<?, ?> ticket) {
            Object ticketId = ticket.get("ticket_id");
            return ticketId == null ? null : ticketId.toString();
        }
        return null;
    }

    private boolean isEmptyKnowledgeResult(Object data) {
        if (data == null) {
            return true;
        }
        if (data instanceof List<?> results) {
            return results.isEmpty();
        }
        return false;
    }

    private long elapsedMillis(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        return Math.max(0, elapsedNanos / 1_000_000);
    }
}
