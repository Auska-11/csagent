package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.tools.OrderTools;
import com.smartcs.smart_cs_agent.tools.ProductTools;
import com.smartcs.smart_cs_agent.tools.ReturnTools;
import com.smartcs.smart_cs_agent.tools.UserTools;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Component;

@Component
public class AgentFactory {

    private final CustomerServiceAgent agent;
    private final SkillConfig skillConfig;

    public AgentFactory(
            ChatLanguageModel chatModel,
            OrderTools orderTools,
            UserTools userTools,
            ProductTools productTools,
            ReturnTools returnTools,
            SkillConfig skillConfig) {

        this.skillConfig = skillConfig;

        this.agent = AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(chatModel)
                .tools(orderTools, userTools, productTools, returnTools)
                .build();
    }

    public CustomerServiceAgent getAgent() {
        return agent;
    }

    public String getSystemPrompt() {
        return skillConfig.getSystemPrompt();
    }
}