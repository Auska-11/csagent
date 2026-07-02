package com.smartcs.smart_cs_agent.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带记忆的聊天控制器 - Agent 学习示例
 *
 * 学习要点：
 * 1. SystemMessage - 定义 AI 的角色
 * 2. ChatMemory - 管理对话历史
 * 3. 多轮对话的实现
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatLanguageModel chatModel;

    // 为每个用户维护独立的对话记忆（简化版，生产环境应使用 Redis 等）
    private final Map<String, ChatMemory> userMemories = new ConcurrentHashMap<>();

    // 系统提示词 - 定义客服 Agent 的行为
    private static final String SYSTEM_PROMPT = """
            你是一个专业的智能客服助手，名叫"小智"。

            你的职责：
            1. 友好、专业地回答用户问题
            2. 帮助用户查询订单、处理售后问题
            3. 当无法解决问题时，建议转接人工客服

            回复要求：
            - 简洁明了，不超过100字
            - 使用亲切的语气
            - 如果不确定答案，诚实告知
            """;

    public ChatController(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 带记忆的多轮对话接口
     *
     * @param userId  用户标识（简化版，实际应从认证中获取）
     * @param message 用户消息
     * @return AI 回复
     */
    @PostMapping("/message")
    public Map<String, String> chat(@RequestParam String userId,
                                     @RequestParam String message) {

        // 1. 获取或创建该用户的对话记忆
        ChatMemory chatMemory = userMemories.computeIfAbsent(
            userId,
            k -> MessageWindowChatMemory.withMaxMessages(20)  // 保留最近20条消息
        );

        // 2. 添加系统提示（如果是新对话）
        if (chatMemory.messages().isEmpty()) {
            chatMemory.add(SystemMessage.from(SYSTEM_PROMPT));
        }

        // 3. 添加用户消息到记忆
        chatMemory.add(UserMessage.from(message));

        // 4. 获取所有历史消息，发送给 LLM
        List<ChatMessage> messages = chatMemory.messages();
        Response<AiMessage> response = chatModel.generate(messages);

        // 5. 获取 AI 回复
        String reply = response.content().text();

        // 6. 将 AI 回复也加入记忆
        chatMemory.add(AiMessage.from(reply));

        return Map.of(
            "userId", userId,
            "userMessage", message,
            "aiReply", reply,
            "messageCount", String.valueOf(chatMemory.messages().size())
        );
    }

    /**
     * 清除用户的对话记忆
     */
    @DeleteMapping("/memory")
    public Map<String, String> clearMemory(@RequestParam String userId) {
        userMemories.remove(userId);
        return Map.of("status", "success", "message", "对话记忆已清除");
    }

    /**
     * 查看当前对话记忆（调试用）
     */
    @GetMapping("/memory")
    public Map<String, Object> getMemory(@RequestParam String userId) {
        ChatMemory chatMemory = userMemories.get(userId);
        if (chatMemory == null) {
            return Map.of("status", "no_memory", "message", "暂无对话记录");
        }

        List<String> messages = chatMemory.messages().stream()
            .map(m -> m.type() + ": " + m.toString().substring(0, Math.min(50, m.toString().length())))
            .toList();

        return Map.of(
            "status", "exists",
            "userId", userId,
            "messageCount", messages.size(),
            "messages", messages
        );
    }
}
