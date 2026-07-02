package com.smartcs.smart_cs_agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SkillConfig {

    @Value("classpath:prompts/system-prompt.md")
    private Resource systemPromptResource;

    public String getSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "You are a professional customer service assistant named XiaoZhi.";
        }
    }
}