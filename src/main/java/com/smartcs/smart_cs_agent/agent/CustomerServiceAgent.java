package com.smartcs.smart_cs_agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface CustomerServiceAgent {

    @SystemMessage("{{systemPrompt}}")
    String chat(@MemoryId Object memoryId, @V("systemPrompt") String systemPrompt, @UserMessage String userMessage);
}