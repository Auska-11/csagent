package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.*;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.skill.Skill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentRuntime {

    private final SkillManager skillManager;
    private final ChatLanguageModel chatModel;
    private final ToolManager toolManager;

    public AgentRuntime(SkillManager skillManager, ChatLanguageModel chatModel, ToolManager toolManager) {
        this.skillManager = skillManager;
        this.chatModel = chatModel;
        this.toolManager = toolManager;
    }

    public String runTurn(ConversationState state) {
        Skill skill = skillManager.getActiveSkill();

        int maxIterations = 10;
        for (int i = 0; i < maxIterations; i++) {
            AgentAction action = skill.nextAction(state);

            if (action instanceof FinalAnswerAction) {
                String answer = ((FinalAnswerAction) action).getAnswer();
                state.addAssistantMessage(answer);
                return answer;
            }

            if (action instanceof AskUserAction) {
                return ((AskUserAction) action).getQuestion();
            }

            if (action instanceof LLMAction) {
                LLMAction llmAction = (LLMAction) action;
                // Save the purpose to state before executing
                state.setTaskState("last_llm_purpose", llmAction.getPurpose());
                executeLLMAction(llmAction, state);
            }

            if (action instanceof CapabilityAction) {
                executeCapabilityAction((CapabilityAction) action, state);
            }
        }

        return "Sorry, processing timeout. Please try again.";
    }

    private void executeLLMAction(LLMAction action, ConversationState state) {
        String purpose = action.getPurpose();
        String prompt = action.getPrompt();

        List<ChatMessage> messages = new ArrayList<>();

        String systemPrompt = skillManager.getSystemPrompt();
        messages.add(SystemMessage.from(systemPrompt));

        for (Map<String, String> msg : state.getMessages()) {
            if ("user".equals(msg.get("role"))) {
                messages.add(UserMessage.from(msg.get("content")));
            } else if ("assistant".equals(msg.get("role"))) {
                messages.add(AiMessage.from(msg.get("content")));
            }
        }

        messages.add(UserMessage.from(prompt));

        Response<AiMessage> response = chatModel.generate(messages);
        String result = response.content().text();

        state.addObservation("llm_response", result);
        state.setTaskState(purpose + "_result", result);
    }

    private void executeCapabilityAction(CapabilityAction action, ConversationState state) {
        String capability = action.getCapability();
        Map<String, Object> arguments = action.getArguments();

        Object result = toolManager.execute(capability, arguments);

        state.addObservation("capability", result);
        state.setTaskState("last_capability", capability);
        state.setTaskState("last_capability_result", result);
    }
}