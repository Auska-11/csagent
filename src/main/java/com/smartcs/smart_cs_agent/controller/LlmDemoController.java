package com.smartcs.smart_cs_agent.controller;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class LlmDemoController {

    private final ChatLanguageModel chatModel;

    public LlmDemoController(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/chat")
    public Map<String, String> chat(@RequestParam String message) {
        String reply = chatModel.generate(message);
        return Map.of("input", message, "reply", reply);
    }
}