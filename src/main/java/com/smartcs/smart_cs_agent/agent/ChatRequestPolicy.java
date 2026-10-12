package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;

/** Channel-specific request constraints are checked before execution or inbox admission. */
public interface ChatRequestPolicy {
    void validate(ChatRequest request);
}
