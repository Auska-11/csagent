package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.AgentFactory;
import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final CustomerServiceAgent agent;
    private final AgentFactory agentFactory;

    public ChatApiController(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
        this.agent = agentFactory.getAgent();
    }

    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String userId = request.get("userId");

        if (message == null || message.isBlank()) {
            return Map.of("error", "Message cannot be empty");
        }

        String systemPrompt = agentFactory.getSystemPrompt();
        String reply = agent.chat("default", systemPrompt, message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("userId", userId != null ? userId : "anonymous");
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    @GetMapping("/users")
    public List<Map<String, String>> getUsers() {
        return List.of(
            Map.of("userId", "USER-001", "name", "Zhang San", "token", "token-zhangsan"),
            Map.of("userId", "USER-002", "name", "Li Si", "token", "token-lisi")
        );
    }
}