package com.smartcs.smart_cs_agent.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM Demo Controller - 多轮对话版本
 *
 * 学习要点：
 * 1. SystemMessage - 定义 AI 的角色
 * 2. MessageWindowChatMemory - 滑动窗口记忆，只保留最近 N 条消息
 */
@RestController
@RequestMapping("/api/demo")
public class LlmDemoController {

    private final ChatLanguageModel chatModel;

    /**
     * 对话记忆：存储历史消息
     * MessageWindowChatMemory.withMaxMessages(20) 表示只保留最近 20 条消息
     * 超出 20 条的旧消息会被自动丢弃，解决 token 超限问题
     */
    private final MessageWindowChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(20);

    /**
     * System Prompt：定义客服角色
     * 这条消息会作为第一条消息发给 LLM，告诉它"你是谁"
     */
    private static final String SYSTEM_PROMPT =
            "You are a professional customer service assistant named XiaoZhi. " +
            "Your responsibilities: " +
            "1. Answer user questions in a friendly and professional manner. " +
            "2. Help users with product and order related issues. " +
            "3. Keep responses concise and warm. " +
            "If you are not sure about the answer, be honest with the user.";

    public LlmDemoController(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
        // 初始化时添加系统提示到记忆
        chatMemory.add(SystemMessage.from(SYSTEM_PROMPT));
    }

    /**
     * 多轮对话接口
     *
     * 请求示例：
     *   GET /api/demo/chat?message=我的订单号是ORD-001
     *   GET /api/demo/chat?message=发货了吗？  （AI会记得之前的订单号）
     *
     * @param message 用户消息
     * @return AI 回复
     */
    @GetMapping("/chat")
    public Map<String, String> chat(@RequestParam String message) {
        // 1. 将用户消息添加到记忆
        chatMemory.add(UserMessage.from(message));

        // 2. 获取所有历史消息（包括系统提示、历史对话）
        List<ChatMessage> messages = chatMemory.messages();

        // 3. 将完整的消息列表发给 LLM
        Response<AiMessage> response = chatModel.generate(messages);

        // 4. 获取 AI 回复
        String reply = response.content().text();

        // 5. 将 AI 回复也添加到记忆（下次对话时 AI 能记得自己说了什么）
        chatMemory.add(AiMessage.from(reply));

        return Map.of("input", message, "reply", reply);
    }
}