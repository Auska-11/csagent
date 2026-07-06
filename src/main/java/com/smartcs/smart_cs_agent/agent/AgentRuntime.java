package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.*;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.skill.Skill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 * 1. 接收 ConversationState（会话状态）
 * 2. 循环调用 Skill.nextAction() 获取下一步操作
 * 3. 根据 Action 类型执行对应逻辑
 * 4. 更新 ConversationState
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
     * 【执行流程】
     * 1. 获取当前 Skill
     * 2. 循环调用 Skill.nextAction() 获取 Action
     * 3. 根据 Action 类型执行：
     *    - FinalAnswerAction → 返回最终回复，结束循环
     *    - AskUserAction → 返回追问问题，结束循环
     *    - LLMAction → 调用 LLM，继续循环
     *    - CapabilityAction → 调用工具，继续循环
     *
     * @param state 会话状态（包含用户消息、历史对话、观察结果等）
     * @return 最终回复或追问问题
     */
    public String runTurn(ConversationState state) {
        // 1. 获取当前活跃的 Skill
        Skill skill = skillManager.getActiveSkill();

        // 2. 循环执行（最多 10 次，防止死循环）
        int maxIterations = 10;
        for (int i = 0; i < maxIterations; i++) {
            // 3. Skill 决定下一步操作
            AgentAction action = skill.nextAction(state);

            // 4. 根据 Action 类型执行

            // 如果是最终回复，停止循环，返回结果
            if (action instanceof FinalAnswerAction) {
                String answer = ((FinalAnswerAction) action).getAnswer();
                state.addAssistantMessage(answer);  // 保存到历史记录
                return answer;
            }

            // 如果是追问用户，停止循环，返回问题
            if (action instanceof AskUserAction) {
                return ((AskUserAction) action).getQuestion();
            }

            // 如果是 LLM 调用，执行后继续循环
            if (action instanceof LLMAction) {
                LLMAction llmAction = (LLMAction) action;
                // 保存 purpose 到状态，供下次循环判断
                state.setTaskState("last_llm_purpose", llmAction.getPurpose());
                executeLLMAction(llmAction, state);
            }

            // 如果是工具调用，执行后继续循环
            if (action instanceof CapabilityAction) {
                executeCapabilityAction((CapabilityAction) action, state);
            }
        }

        // 超过最大循环次数，返回超时提示
        return "Sorry, processing timeout. Please try again.";
    }

    /**
     * 执行 LLM 调用
     *
     * 【执行流程】
     * 1. 构建消息列表（System Prompt + 历史对话 + 当前 Prompt）
     * 2. 调用 LLM 生成回复
     * 3. 保存结果到 ConversationState
     *
     * @param action LLMAction（包含 purpose 和 prompt）
     * @param state 会话状态
     */
    private void executeLLMAction(LLMAction action, ConversationState state) {
        String purpose = action.getPurpose();  // 用途：intent_recognition 或 response_generation
        String prompt = action.getPrompt();    // 提示词

        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();

        // 添加 System Prompt（从 SkillManager 获取）
        String systemPrompt = skillManager.getSystemPrompt();
        messages.add(SystemMessage.from(systemPrompt));

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

        // 调用 LLM
        Response<AiMessage> response = chatModel.generate(messages);
        String result = response.content().text();

        // 保存结果到状态
        state.addObservation("llm_response", result);  // 添加到观察结果
        state.setTaskState(purpose + "_result", result);  // 保存到任务状态
    }

    /**
     * 执行工具调用
     *
     * 【执行流程】
     * 1. 从 Action 获取工具名称和参数
     * 2. 调用 ToolManager 执行工具
     * 3. 保存结果到 ConversationState
     *
     * @param action CapabilityAction（包含 capability 和 arguments）
     * @param state 会话状态
     */
    private void executeCapabilityAction(CapabilityAction action, ConversationState state) {
        String capability = action.getCapability();  // 工具名称：order_lookup, product_search 等
        Map<String, Object> arguments = action.getArguments();  // 参数：order_id, keyword 等

        // 调用工具管理器执行工具
        Object result = toolManager.execute(capability, arguments);

        // 保存结果到状态
        state.addObservation("capability", result);  // 添加到观察结果
        state.setTaskState("last_capability", capability);  // 记录最后调用的工具
        state.setTaskState("last_capability_result", result);  // 保存工具结果
    }
}