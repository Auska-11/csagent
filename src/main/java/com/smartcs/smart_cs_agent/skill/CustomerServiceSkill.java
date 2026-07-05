package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.action.*;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class CustomerServiceSkill implements Skill {

    private final ChatLanguageModel chatModel;

    public CustomerServiceSkill(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String getName() {
        return "customer_service";
    }

    @Override
    public float canHandle(String message) {
        return 0.8f;
    }

    @Override
    public AgentAction nextAction(ConversationState state) {
        String message = state.getMessage();
        Observation capabilityObs = state.getLatestObservationByType("capability");
        Observation llmObs = state.getLatestObservationByType("llm_response");
        String lastPurpose = (String) state.getTaskState("last_llm_purpose");

        // If we have an LLM result from response_generation, return it
        if (llmObs != null && "response_generation".equals(lastPurpose)) {
            String llmResult = llmObs.getData().toString();
            return new FinalAnswerAction(llmResult);
        }

        // If we have a tool result, generate response from it
        if (capabilityObs != null && state.getTaskState("response_generated") == null) {
            state.setTaskState("response_generated", true);
            return generateResponseFromToolResult(state);
        }

        // Try rule-based routing first
        AgentAction ruleAction = ruleRoute(message, state);
        if (ruleAction != null) {
            return ruleAction;
        }

        // Fall back to LLM-based routing
        return llmRoute(state);
    }

    private AgentAction ruleRoute(String message, ConversationState state) {
        String lowerMessage = message.toLowerCase();

        // Order query
        if (containsAny(lowerMessage, "order", "logistics", "delivery")) {
            String orderId = extractOrderId(message);
            if (orderId != null) {
                state.setActiveIntent("order_query");
                state.setTaskState("order_id", orderId);
                return new CapabilityAction("order_lookup", Map.of("order_id", orderId));
            } else {
                return new AskUserAction("What is your order ID?");
            }
        }

        // Product query
        if (containsAny(lowerMessage, "product", "price", "recommend")) {
            state.setActiveIntent("product_query");
            String keyword = extractProductKeyword(message);
            return new CapabilityAction("product_search", Map.of("keyword", keyword != null ? keyword : message));
        }

        // Return/Refund
        if (containsAny(lowerMessage, "return", "refund")) {
            String orderId = extractOrderId(message);
            if (orderId != null) {
                state.setActiveIntent("return_apply");
                state.setTaskState("order_id", orderId);
                return new CapabilityAction("return_apply", Map.of("order_id", orderId));
            } else {
                return new AskUserAction("Which order do you want to return?");
            }
        }

        // User info
        if (containsAny(lowerMessage, "my info", "profile", "account")) {
            state.setActiveIntent("user_info");
            return new CapabilityAction("user_lookup", Map.of());
        }

        // Chitchat - use LLM directly
        if (containsAny(lowerMessage, "hello", "hi", "thanks", "bye")) {
            state.setActiveIntent("chitchat");
            return new LLMAction("response_generation", buildChitchatPrompt(message, state), new HashMap<>());
        }

        // No rule matched
        return null;
    }

    private AgentAction llmRoute(ConversationState state) {
        String prompt = buildIntentRecognitionPrompt(state);
        return new LLMAction("intent_recognition", prompt, new HashMap<>());
    }

    private AgentAction generateResponseFromToolResult(ConversationState state) {
        String prompt = buildResponseGenerationPrompt(state);
        return new LLMAction("response_generation", prompt, new HashMap<>());
    }

    private String buildIntentRecognitionPrompt(ConversationState state) {
        return "You are an intent classifier. Reply with intent name only. User: " + state.getMessage();
    }

    private String buildChitchatPrompt(String message, ConversationState state) {
        return "You are XiaoZhi, a customer service assistant. Reply friendly. User: " + message;
    }

    private String buildResponseGenerationPrompt(ConversationState state) {
        Observation toolResult = state.getLatestObservationByType("capability");
        String resultStr = toolResult != null ? toolResult.getData().toString() : "No result";
        return "You are XiaoZhi. Generate response based on: " + resultStr;
    }

    private String extractOrderId(String message) {
        String upper = message.toUpperCase();
        int idx = upper.indexOf("ORD-");
        if (idx >= 0 && idx + 7 <= upper.length()) {
            String num = upper.substring(idx + 4, idx + 7);
            try {
                Integer.parseInt(num);
                return "ORD-" + num;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String extractProductKeyword(String message) {
        String[] keywords = {"headset", "watch", "charger", "cable", "bluetooth", "smart", "wireless"};
        for (String keyword : keywords) {
            if (message.toLowerCase().contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}