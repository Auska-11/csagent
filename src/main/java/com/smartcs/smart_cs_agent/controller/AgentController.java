package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.tools.OrderTools;
import com.smartcs.smart_cs_agent.tools.ProductTools;
import com.smartcs.smart_cs_agent.tools.ReturnTools;
import com.smartcs.smart_cs_agent.tools.UserTools;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 控制器 - Phase 2 的核心！
 *
 * 【这个类做了什么？】
 * 把所有工具（OrderTools、UserTools、ProductTools、ReturnTools）注册到 Agent，
 * 让 LLM 能够自动调用这些工具。
 *
 * 【核心知识点：AiServices】
 * AiServices 是 LangChain4j 的核心 API，它做了两件事：
 * 1. 把 Java 接口变成 Agent（自动实现接口方法）
 * 2. 自动处理工具调用循环
 *
 * 【工具调用循环】
 * 用户消息 -> LLM -> 决定调用工具 -> 执行工具 -> 结果给 LLM -> LLM 回复
 * 这个循环可能多次（LLM 可能连续调用多个工具）
 *
 * 【与 LlmDemoController 的区别】
 * - LlmDemoController: 手动管理消息和记忆，不支持工具调用
 * - AgentController: 用 AiServices 自动管理，支持工具调用
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /**
     * Agent 接口定义
     *
     * 【这是什么？】
     * 定义 Agent 的行为契约：接收用户消息，返回回复
     *
     * 【为什么用接口？】
     * AiServices 会在运行时自动实现这个接口
     * 你不需要写实现类，LangChain4j 帮你搞定
     *
     * 【@SystemMessage】
     * 定义 Agent 的角色和行为规则
     * 每次对话都会作为第一条消息发给 LLM
     */
    interface CustomerServiceAgent {

        @SystemMessage("""
                You are a professional customer service assistant named XiaoZhi.
                Your responsibilities:
                1. Help users check order status and logistics info
                2. Help users query product info (price, stock, description)
                3. Help users apply for product return service
                4. Help users query personal info

                Response requirements:
                - Friendly, professional, concise
                - If user does not provide enough info, ask proactively
                - If query returns no result, suggest user check info
                """)
        String chat(@UserMessage String userMessage);
    }

    /**
     * Agent 实例
     * 由 AiServices 创建，自动实现 CustomerServiceAgent 接口
     */
    private final CustomerServiceAgent agent;

    /**
     * 构造函数 - 创建 Agent 并注册所有工具
     *
     * 【AiServices.builder() 做了什么？】
     * 1. .chatLanguageModel(chatModel) - 设置 LLM 模型
     * 2. .tools(...) - 注册工具，LLM 可以调用这些工具
     * 3. .build() - 创建 Agent 实例
     *
     * 【注册工具后发生了什么？】
     * AiServices 会扫描所有 @Tool 注解的方法，
     * 把方法名、描述、参数信息提取出来，
     * 当 LLM 收到用户消息时，这些信息会告诉 LLM 有哪些工具可用。
     */
    public AgentController(
            dev.langchain4j.model.chat.ChatLanguageModel chatModel,
            OrderTools orderTools,
            UserTools userTools,
            ProductTools productTools,
            ReturnTools returnTools) {

        // 创建 Agent，注册所有工具
        this.agent = AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(chatModel)
                .tools(orderTools, userTools, productTools, returnTools)
                .build();
    }

    /**
     * 对话接口
     *
     * 【请求方式】POST /api/agent/chat
     * 【请求体】{"message": "帮我查一下订单 ORD-001"}
     * 【返回值】{"input": "...", "reply": "..."}
     *
     * 【执行流程】
     * 1. 收到用户消息
     * 2. 调用 agent.chat(message)
     * 3. AiServices 自动处理：
     *    - 把消息发给 LLM
     *    - LLM 决定是否调用工具
     *    - 如果调用工具，执行后把结果再给 LLM
     *    - 最终返回 LLM 的回复
     * 4. 返回给前端
     */
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");

        if (message == null || message.isBlank()) {
            return Map.of("error", "Message cannot be empty");
        }

        // 调用 Agent - 这一行代码就搞定了所有事情！
        // AiServices 会自动处理工具调用循环
        String reply = agent.chat(message);

        return Map.of(
                "input", message,
                "reply", reply
        );
    }
}