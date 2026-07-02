package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.tools.OrderTools;
import com.smartcs.smart_cs_agent.tools.ProductTools;
import com.smartcs.smart_cs_agent.tools.ReturnTools;
import com.smartcs.smart_cs_agent.tools.UserTools;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂 - 统一创建和管理 Agent 实例
 *
 * 【为什么需要工厂？】
 * 之前：每个 Controller 自己创建 Agent，导致：
 * 1. 重复代码（两个 Controller 都有创建逻辑）
 * 2. 多个 Agent 实例（浪费资源）
 * 3. 难以统一管理（改工具注册要改两个地方）
 *
 * 现在：用工厂统一管理：
 * 1. 只创建一个 Agent 实例
 * 2. 所有 Controller 共享
 * 3. 工具注册集中管理
 *
 * 【构造函数注入】
 * Spring 会自动注入所有依赖：
 * - chatModel: LLM 模型（配置在 application.yml）
 * - orderTools, userTools, productTools, returnTools: 业务工具
 * - skillConfig: 配置加载器
 *
 * 【AiServices.builder()】
 * LangChain4j 的核心 API，用来创建 Agent：
 * 1. .chatLanguageModel(chatModel) - 设置 LLM 模型
 * 2. .tools(...) - 注册工具，LLM 可以调用这些工具
 * 3. .build() - 创建 Agent 实例
 *
 * 【getAgent() 和 getSystemPrompt()】
 * Controller 通过这两个方法获取 Agent 和 System Prompt。
 */
@Component
public class AgentFactory {

    /**
     * Agent 实例 - 所有 Controller 共享
     * 使用 final 保证创建后不可变
     */
    private final CustomerServiceAgent agent;

    /**
     * 配置加载器 - 用于获取 System Prompt
     */
    private final SkillConfig skillConfig;

    /**
     * 构造函数 - 创建 Agent 并注册所有工具
     *
     * Spring 会自动注入所有参数，不需要手动 new
     */
    public AgentFactory(
            ChatLanguageModel chatModel,
            OrderTools orderTools,
            UserTools userTools,
            ProductTools productTools,
            ReturnTools returnTools,
            SkillConfig skillConfig) {

        // 保存配置加载器
        this.skillConfig = skillConfig;

        // 创建 Agent，注册所有工具
        // AiServices 会自动扫描工具类中的 @Tool 注解
        this.agent = AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(chatModel)
                .tools(orderTools, userTools, productTools, returnTools)
                .build();
    }

    /**
     * 获取 Agent 实例
     *
     * Controller 调用这个方法获取 Agent，然后调用 agent.chat() 对话
     */
    public CustomerServiceAgent getAgent() {
        return agent;
    }

    /**
     * 获取 System Prompt
     *
     * 从 SkillConfig 加载，内容来自 prompts/system-prompt.md 文件
     * Controller 调用这个方法获取 Prompt，传给 agent.chat()
     */
    public String getSystemPrompt() {
        return skillConfig.getSystemPrompt();
    }
}