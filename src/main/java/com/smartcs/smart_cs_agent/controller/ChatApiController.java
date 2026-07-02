package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.AgentFactory;
import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 前端聊天 API Controller
 *
 * 【与 AgentController 的区别】
 * - AgentController: 简单的 API，只返回回复
 * - ChatApiController: 为前端设计，返回更多字段（userId、timestamp）
 *
 * 【改造前 vs 改造后】
 * 改造前：自己定义 Agent 接口和创建逻辑
 * 改造后：从 AgentFactory 获取，代码更简洁
 *
 * 【API 设计】
 * POST /api/chat/send - 发送消息
 * GET  /api/chat/users - 获取用户列表（前端用户切换用）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    /**
     * Agent 实例 - 从 AgentFactory 获取
     */
    private final CustomerServiceAgent agent;

    /**
     * AgentFactory - 用于获取 System Prompt
     */
    private final AgentFactory agentFactory;

    /**
     * 构造函数注入
     */
    public ChatApiController(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
        this.agent = agentFactory.getAgent();
    }

    /**
     * 发送消息接口
     *
     * @param request {"message": "...", "userId": "USER-001"}
     * @return {"reply": "...", "userId": "...", "timestamp": ...}
     */
    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String userId = request.get("userId");

        if (message == null || message.isBlank()) {
            return Map.of("error", "Message cannot be empty");
        }

        // 获取 System Prompt
        String systemPrompt = agentFactory.getSystemPrompt();

        // 调用 Agent 对话
        String reply = agent.chat("default", systemPrompt, message);

        // 构造返回结果
        // 使用 LinkedHashMap 保证字段顺序
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("userId", userId != null ? userId : "anonymous");
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 获取用户列表（前端用户切换用）
     *
     * @return 用户列表
     */
    @GetMapping("/users")
    public List<Map<String, String>> getUsers() {
        // 返回模拟的用户列表
        // 真实项目中会从数据库查询
        return List.of(
            Map.of("userId", "USER-001", "name", "Zhang San", "token", "token-zhangsan"),
            Map.of("userId", "USER-002", "name", "Li Si", "token", "token-lisi")
        );
    }
}