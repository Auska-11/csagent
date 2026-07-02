package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.AgentFactory;
import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final CustomerServiceAgent agent;
    private final AgentFactory agentFactory;

    public AgentController(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
        this.agent = agentFactory.getAgent();
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");

        if (message == null || message.isBlank()) {
            return Map.of("error", "Message cannot be empty");
        }

        String systemPrompt = agentFactory.getSystemPrompt();
        String reply = agent.chat("default", systemPrompt, message);

        return Map.of(
                "input", message,
                "reply", reply
        );
    }
}