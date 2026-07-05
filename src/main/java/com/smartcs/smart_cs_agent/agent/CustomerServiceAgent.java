package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import org.springframework.stereotype.Component;

@Component
public class CustomerServiceAgent {

    private final AgentRuntime runtime;
    private final SkillManager skillManager;

    public CustomerServiceAgent(AgentRuntime runtime, SkillManager skillManager, CustomerServiceSkill customerServiceSkill) {
        this.runtime = runtime;
        this.skillManager = skillManager;
        skillManager.registerSkill(customerServiceSkill);
    }

    public String chat(String message) {
        ConversationState state = new ConversationState(message);
        return runtime.runTurn(state);
    }

    public String chat(String message, String userId) {
        ConversationState state = new ConversationState(message, userId);
        return runtime.runTurn(state);
    }
}