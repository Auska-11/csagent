package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.action.*;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 客服 Skill：负责“决策下一步做什么”。
 *
 * 【你可以这样理解】
 * AgentRuntime 像执行引擎，只负责把 Action 跑起来；
 * CustomerServiceSkill 像客服大脑，负责判断：
 * - 这句话是不是查订单？
 * - 是否需要调用工具？
 * - 是否需要追问用户？
 * - 工具结果拿到后，是否需要让 LLM 整理成自然语言？
 *
 * 【当前策略】
 * 1. 先用规则识别常见意图：快、稳定、省 token。
 * 2. 规则识别不了，再交给 LLM 做语义兜底。
 * 3. 工具返回业务数据后，再让 LLM 生成中文客服回复。
 */
@Component
public class CustomerServiceSkill implements Skill {

    /**
     * 当前没有直接在本类里调用 chatModel。
     * 它由 Spring 注入，保留下来是为了后续扩展：
     * 比如把部分简单 LLM 调用从 Runtime 下沉到 Skill。
     */
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
        // 目前项目只有一个客服 Skill，所以直接给一个较高分。
        // 以后如果有售前 Skill、售后 Skill、投诉 Skill，可以根据 message 动态打分。
        return 0.8f;
    }

    /**
     * Skill 的核心方法：根据当前会话状态，返回下一步 Action。
     *
     * 注意：这里“不直接执行工具”和“不直接调用 LLM”。
     * 它只返回 Action，真正执行由 AgentRuntime 统一处理。
     * 这样做的好处是：决策和执行解耦，后续更容易扩展、测试和排查问题。
     */
    @Override
    public AgentAction nextAction(ConversationState state) {
        String message = state.getMessage();
        Observation capabilityObs = state.getLatestObservationByType("capability");
        Observation llmObs = state.getLatestObservationByType("llm_response");
        String lastPurpose = (String) state.getTaskState("last_llm_purpose");

        // 如果上一轮 LLM 的任务是“生成最终回复”，说明可以直接返回给用户了。
        if (llmObs != null && "response_generation".equals(lastPurpose)) {
            return new FinalAnswerAction(llmObs.getData().toString());
        }

        // 如果上一轮 LLM 的任务是“识别意图”，需要把意图结果转成具体动作。
        // 例如 LLM 返回 ORDER_QUERY，这里再决定是查订单工具，还是追问订单号。
        if (llmObs != null && "intent_recognition".equals(lastPurpose)
                && state.getTaskState("intent_handled") == null) {
            state.setTaskState("intent_handled", true);
            AgentAction intentAction = actionFromIntent(llmObs.getData().toString(), state);
            if (intentAction != null) {
                return intentAction;
            }
            return new AskUserAction("我可以帮您查询订单、商品信息、退换货进度或个人信息，请问您想办理哪一项？");
        }

        // 如果工具已经执行完毕，就让 LLM 把工具结果整理成用户能看懂的客服话术。
        if (capabilityObs != null && state.getTaskState("response_generated") == null) {
            state.setTaskState("response_generated", true);
            return generateResponseFromToolResult(state);
        }

        // 优先走规则路由。规则能覆盖的场景不要急着调用 LLM，成本更低、行为更可控。
        AgentAction ruleAction = ruleRoute(message, state);
        if (ruleAction != null) {
            return ruleAction;
        }

        // 规则无法判断时，再让 LLM 识别意图，作为语义兜底。
        return llmRoute(state);
    }

    /**
     * 规则路由：用关键词判断用户意图。
     *
     * 这是客服 Agent 很常见的工程策略：
     * 高频、明确、业务强相关的场景使用规则；
     * 复杂、模糊、表达多变的场景再交给 LLM。
     */
    private AgentAction ruleRoute(String message, ConversationState state) {
        String lowerMessage = message.toLowerCase();

        // 订单查询：支持中文和英文关键词。
        if (containsAny(lowerMessage, "order", "logistics", "delivery", "订单", "物流", "快递", "发货", "到哪")) {
            // 如果用户问“我的订单”，通常是查当前用户的全部订单，不需要订单号。
            if (containsAny(lowerMessage, "my order", "my orders", "我的订单", "全部订单", "查看我的订单", "有哪些订单")) {
                state.setActiveIntent("order_query");
                return new CapabilityAction("user_orders", Map.of("user_id", state.getUserId()));
            }

            // 如果用户提供了 ORD-001 这种订单号，就直接调用订单查询工具。
            String orderId = extractOrderId(message);
            if (orderId != null) {
                state.setActiveIntent("order_query");
                state.setTaskState("order_id", orderId);
                return new CapabilityAction("order_lookup", Map.of("order_id", orderId));
            }
            return new AskUserAction("请提供订单号，例如 ORD-001，我来帮您查询订单状态。");
        }

        // 商品咨询：包括价格、库存、推荐、具体品类等表达。
        if (containsAny(lowerMessage, "product", "price", "recommend", "产品", "商品", "价格", "多少钱",
                "有货", "库存", "推荐", "耳机", "手表", "充电器", "数据线", "蓝牙", "无线")) {
            state.setActiveIntent("product_query");
            String keyword = extractProductKeyword(message);
            return new CapabilityAction("product_search", Map.of("keyword", keyword != null ? keyword : message));
        }

        // 退换货进度查询：需要退换货单号 RET-001。
        if (containsAny(lowerMessage, "return status", "return progress", "退货进度", "退换货状态", "退换货进度")) {
            String returnId = extractReturnId(message);
            if (returnId != null) {
                state.setActiveIntent("return_query");
                return new CapabilityAction("return_query", Map.of("return_id", returnId));
            }
            return new AskUserAction("请提供退换货单号，例如 RET-001，我来帮您查询处理进度。");
        }

        // 申请退换货：需要订单号 ORD-001。
        if (containsAny(lowerMessage, "return", "refund", "退货", "退款", "换货", "退换", "质量问题")) {
            String orderId = extractOrderId(message);
            if (orderId != null) {
                state.setActiveIntent("return_apply");
                state.setTaskState("order_id", orderId);
                return new CapabilityAction("return_apply", Map.of("order_id", orderId));
            }
            return new AskUserAction("请提供需要退换货的订单号，例如 ORD-004。");
        }

        // 用户信息查询：使用当前会话里的 userId 作为身份标识。
        if (containsAny(lowerMessage, "my info", "profile", "account", "我的信息", "账号", "个人信息", "我是谁")) {
            state.setActiveIntent("user_info");
            return new CapabilityAction("user_lookup", Map.of("token", state.getUserId()));
        }

        // 闲聊不需要业务工具，直接让 LLM 生成友好的客服回复。
        if (containsAny(lowerMessage, "hello", "hi", "thanks", "bye", "你好", "在吗", "谢谢", "再见")) {
            state.setActiveIntent("chitchat");
            return new LLMAction("response_generation", buildChitchatPrompt(message), new HashMap<>());
        }

        return null;
    }

    /**
     * LLM 意图识别入口。
     *
     * 这里只构造 LLMAction，不直接调用模型。
     * Runtime 执行完 LLMAction 后，会把结果放回 ConversationState，
     * 下一轮 nextAction 再通过 actionFromIntent() 继续处理。
     */
    private AgentAction llmRoute(ConversationState state) {
        return new LLMAction("intent_recognition", buildIntentRecognitionPrompt(state), new HashMap<>());
    }

    /**
     * 把 LLM 识别出的意图名称，转换成真正可执行的 Action。
     *
     * 例如：
     * - ORDER_QUERY + 有订单号 -> 调用 order_lookup
     * - ORDER_QUERY + 没订单号 -> 追问订单号
     * - CHITCHAT -> 让 LLM 直接生成回复
     */
    private AgentAction actionFromIntent(String intentResult, ConversationState state) {
        String intent = intentResult == null ? "" : intentResult.trim().toUpperCase();

        if (intent.contains("ORDER_QUERY")) {
            state.setActiveIntent("order_query");
            String orderId = extractOrderId(state.getMessage());
            if (orderId != null) {
                return new CapabilityAction("order_lookup", Map.of("order_id", orderId));
            }
            if (containsAny(state.getMessage().toLowerCase(), "我的订单", "my orders", "all orders")) {
                return new CapabilityAction("user_orders", Map.of("user_id", state.getUserId()));
            }
            return new AskUserAction("请提供订单号，例如 ORD-001，我来帮您查询订单状态。");
        }

        if (intent.contains("PRODUCT_QUERY")) {
            state.setActiveIntent("product_query");
            String keyword = extractProductKeyword(state.getMessage());
            return new CapabilityAction("product_search", Map.of("keyword", keyword != null ? keyword : ""));
        }

        if (intent.contains("RETURN_QUERY")) {
            state.setActiveIntent("return_query");
            String returnId = extractReturnId(state.getMessage());
            if (returnId != null) {
                return new CapabilityAction("return_query", Map.of("return_id", returnId));
            }
            return new AskUserAction("请提供退换货单号，例如 RET-001，我来帮您查询处理进度。");
        }

        if (intent.contains("RETURN_APPLY")) {
            state.setActiveIntent("return_apply");
            String orderId = extractOrderId(state.getMessage());
            if (orderId != null) {
                return new CapabilityAction("return_apply", Map.of("order_id", orderId));
            }
            return new AskUserAction("请提供需要退换货的订单号，例如 ORD-004。");
        }

        if (intent.contains("USER_INFO")) {
            state.setActiveIntent("user_info");
            return new CapabilityAction("user_lookup", Map.of("token", state.getUserId()));
        }

        if (intent.contains("CHITCHAT")) {
            state.setActiveIntent("chitchat");
            return new LLMAction("response_generation", buildChitchatPrompt(state.getMessage()), new HashMap<>());
        }

        return null;
    }

    /**
     * 根据工具结果生成客服回复。
     *
     * 工具结果通常比较“系统化”，比如包含字段、英文状态等。
     * 这里交给 LLM 做一次润色，让最终回复更像真实客服。
     */
    private AgentAction generateResponseFromToolResult(ConversationState state) {
        return new LLMAction("response_generation", buildResponseGenerationPrompt(state), new HashMap<>());
    }

    /**
     * 构造意图识别 Prompt。
     *
     * 要求 LLM 只返回一个意图名，是为了让后续代码更容易解析。
     * 如果让 LLM 返回大段解释，程序就很难稳定处理。
     */
    private String buildIntentRecognitionPrompt(ConversationState state) {
        return """
                You are an intent classifier for an ecommerce customer service agent.
                Reply with exactly one intent name only:
                ORDER_QUERY, PRODUCT_QUERY, RETURN_APPLY, RETURN_QUERY, USER_INFO, CHITCHAT, OTHER.

                User message: %s
                """.formatted(state.getMessage());
    }

    /**
     * 构造闲聊回复 Prompt。
     */
    private String buildChitchatPrompt(String message) {
        return "你是电商客服小智，请用中文友好、简洁地回复用户。用户：" + message;
    }

    /**
     * 构造工具结果总结 Prompt。
     */
    private String buildResponseGenerationPrompt(ConversationState state) {
        Observation toolResult = state.getLatestObservationByType("capability");
        String resultStr = toolResult != null ? toolResult.getData().toString() : "No result";
        return """
                你是电商客服小智。请根据下面的工具结果，用中文给用户一个友好、专业、简洁的回复。
                不要暴露内部工具名称。

                工具结果：
                %s
                """.formatted(resultStr);
    }

    /**
     * 从用户消息中提取订单号。
     *
     * 当前支持 ORD-001 这种固定格式。
     * 后续可以升级为正则表达式，支持 ORD-0001、ord001 等更多格式。
     */
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

    /**
     * 从用户消息中提取退换货单号。
     *
     * 当前支持 RET-001 这种固定格式。
     */
    private String extractReturnId(String message) {
        String upper = message.toUpperCase();
        int idx = upper.indexOf("RET-");
        if (idx >= 0 && idx + 7 <= upper.length()) {
            String num = upper.substring(idx + 4, idx + 7);
            try {
                Integer.parseInt(num);
                return "RET-" + num;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 提取商品搜索关键词。
     *
     * 例如：
     * - “蓝牙耳机多少钱” -> “耳机”或“蓝牙”
     * - “有什么产品推荐” -> 空字符串，表示搜索全部商品
     */
    private String extractProductKeyword(String message) {
        String lowerMessage = message.toLowerCase();
        String[] keywords = {"耳机", "手表", "充电器", "数据线", "蓝牙", "智能", "无线",
                "headset", "watch", "charger", "cable", "bluetooth", "smart", "wireless"};
        for (String keyword : keywords) {
            if (lowerMessage.contains(keyword)) {
                return keyword;
            }
        }
        if (containsAny(lowerMessage, "推荐", "product", "产品", "商品")) {
            return "";
        }
        return null;
    }

    /**
     * 判断 message 中是否包含任意一个关键词。
     *
     * 这是一个小工具方法，避免在规则判断里写很多重复的 contains。
     */
    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
