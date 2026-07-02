package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.tools.OrderTools;
import com.smartcs.smart_cs_agent.tools.ProductTools;
import com.smartcs.smart_cs_agent.tools.ReturnTools;
import com.smartcs.smart_cs_agent.tools.UserTools;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

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

    private final CustomerServiceAgent agent;
    private final UserRepository userRepository;

    public ChatApiController(
            dev.langchain4j.model.chat.ChatLanguageModel chatModel,
            OrderTools orderTools,
            UserTools userTools,
            ProductTools productTools,
            ReturnTools returnTools,
            UserRepository userRepository) {

        this.userRepository = userRepository;

        this.agent = AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(chatModel)
                .tools(orderTools, userTools, productTools, returnTools)
                .build();
    }

    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String userId = request.get("userId");

        if (message == null || message.isBlank()) {
            return Map.of("error", "Message cannot be empty");
        }

        String reply = agent.chat(message);

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