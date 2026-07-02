package com.smartcs.smart_cs_agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 客服 Agent 接口定义
 *
 * 【这个接口的作用】
 * 定义 Agent 的行为契约：接收用户消息，返回回复。
 * LangChain4j 的 AiServices 会在运行时自动实现这个接口。
 *
 * 【为什么独立出来？】
 * 之前：Agent 接口定义在 Controller 里，两个 Controller 就有两个重复的接口
 * 现在：独立出来，所有 Controller 共享同一个接口，避免重复
 *
 * 【@SystemMessage("{{systemPrompt}}")】
 * 使用模板变量 {{systemPrompt}}，运行时会替换为实际的 System Prompt。
 * 这样 System Prompt 可以从文件加载，不用写死在代码里。
 *
 * 【@MemoryId Object memoryId】
 * 用于区分不同的对话会话。目前传 "default" 即可。
 * 后续做多用户时，可以传 userId 来区分不同用户的对话。
 *
 * 【@V("systemPrompt") String systemPrompt】
 * 给模板变量 {{systemPrompt}} 传值。
 * 调用时传入 SkillConfig 加载的 System Prompt。
 *
 * 【@UserMessage String userMessage】
 * 用户消息。LangChain4j 会自动把这个参数标记为用户消息。
 */
public interface CustomerServiceAgent {

    @SystemMessage("{{systemPrompt}}")
    String chat(@MemoryId Object memoryId, @V("systemPrompt") String systemPrompt, @UserMessage String userMessage);
}