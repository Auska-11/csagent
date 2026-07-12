package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.action.*;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.memory.UserMemoryWriteResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * 下面这些常量是“任务状态”里会反复使用的 key。
     *
     * 为什么不直接在代码里到处写 "order_id"？
     * - 写成常量后，拼写更不容易出错。
     * - 新手读代码时，也能一眼看出这些字符串是有业务含义的。
     */
    private static final String INTENT_ORDER_QUERY = "order_query";
    private static final String INTENT_RETURN_QUERY = "return_query";
    private static final String INTENT_RETURN_APPLY = "return_apply";
    private static final String INTENT_KNOWLEDGE_QUERY = "knowledge_query";
    private static final String INTENT_HANDOFF = "handoff";

    private static final String SLOT_ORDER_ID = "order_id";
    private static final String SLOT_RETURN_ID = "return_id";
    private static final String SLOT_RETURN_REASON = "return_reason";

    /** 槽位抽取器：负责从用户文本中提取订单号、退货单号、退货原因等字段 */
    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();

    /** 意图规则集合：负责判断政策问题、退货问题、转人工问题等关键词规则 */
    private final CustomerServiceIntentRules intentRules = new CustomerServiceIntentRules();

    /** Prompt 构造器：负责把上下文组织成 LLM 能理解的提示词 */
    private final CustomerServicePromptBuilder promptBuilder = new CustomerServicePromptBuilder();

    /**
     * 当前没有直接在本类里调用 chatModel。
     * 它由 Spring 注入，保留下来是为了后续扩展：
     * 比如把部分简单 LLM 调用从 Runtime 下沉到 Skill。
     */
    private final ChatLanguageModel chatModel;

    /**
     * 用户长期记忆仓库。
     *
     * 它保存的是跨会话偏好，例如用户喜欢蓝牙耳机。
     * 和 ConversationState 不同，它不随着某个 conversationId 结束而丢失。
     */
    private final UserMemoryStore userMemoryStore;

    @Autowired
    public CustomerServiceSkill(ChatLanguageModel chatModel, UserMemoryStore userMemoryStore) {
        this.chatModel = chatModel;
        this.userMemoryStore = userMemoryStore;
    }

    /**
     * 测试用构造函数。
     *
     * 很多单元测试只验证规则路由，不启动 Spring 容器。
     * 给它一个默认内存仓库，可以减少测试样板代码。
     */
    public CustomerServiceSkill(ChatLanguageModel chatModel) {
        this(chatModel, new UserMemoryStore());
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
    public AgentAction nextAction(TurnContext context) {
        ConversationState state = context.getState();
        String message = context.getMessage();
        Observation capabilityObs = context.getLatestObservationByType("capability");
        Observation llmObs = context.getLatestObservationByType("llm_response");
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
            AgentAction intentAction = actionFromIntent(llmObs.getData().toString(), context);
            if (intentAction != null) {
                return intentAction;
            }
            return new AskUserAction("我可以帮您查询订单、商品信息、退换货进度或个人信息，请问您想办理哪一项？");
        }

        // 如果工具已经执行完毕，就让 LLM 把工具结果整理成用户能看懂的客服话术。
        if (capabilityObs != null && state.getTaskState("response_generated") == null) {
            state.setTaskState("response_generated", true);
            return generateResponseFromToolResult(context);
        }

        // 转人工要优先于多轮槽位填充。
        //
        // 例如上一轮 Agent 在追问“退货原因”，用户这一轮说“我要人工投诉”，
        // 这时不能把“我要人工投诉”当作退货原因，而应该立即进入人工转接。
        AgentAction handoffAction = handoffRoute(context);
        if (handoffAction != null) {
            return handoffAction;
        }

        // 多轮任务优先级要高于普通规则路由。
        //
        // 例子：
        // 第 1 轮：用户说“我要退货” -> Agent 追问订单号，并记录 pending_slot = order_id
        // 第 2 轮：用户只说“ORD-004”
        //
        // 如果不先检查 pending_slot，单看“ORD-004”这句话，系统并不知道它是在补充退货订单号。
        // 所以这里先处理“上一轮没收集完整的信息”，这就是槽位填充（Slot Filling）。
        AgentAction pendingSlotAction = handlePendingSlot(context);
        if (pendingSlotAction != null) {
            return pendingSlotAction;
        }

        // 长期记忆写入：用户明确表达偏好时，先记录到用户画像。
        //
        // 这一步放在 pending_slot 之后，是为了避免打断正在进行的退货、订单查询等任务。
        AgentAction userMemoryAction = handleUserMemory(context);
        if (userMemoryAction != null) {
            return userMemoryAction;
        }

        // 优先走规则路由。规则能覆盖的场景不要急着调用 LLM，成本更低、行为更可控。
        AgentAction ruleAction = ruleRoute(context);
        if (ruleAction != null) {
            return ruleAction;
        }

        // 规则无法判断时，再让 LLM 识别意图，作为语义兜底。
        return llmRoute(context);
    }

    /**
     * 规则路由：用关键词判断用户意图。
     *
     * 这是客服 Agent 很常见的工程策略：
     * 高频、明确、业务强相关的场景使用规则；
     * 复杂、模糊、表达多变的场景再交给 LLM。
     */
    private AgentAction ruleRoute(TurnContext context) {
        ConversationState state = context.getState();
        String message = context.getMessage();
        String lowerMessage = message.toLowerCase();

        // 政策类 / FAQ 类问题优先走知识库。
        //
        // 例如“超过七天还能退吗？”“发票什么时候开？”这类问题，
        // 不能只靠 LLM 自己猜答案，否则很容易出现政策幻觉。
        // 先检索知识库，再让 LLM 基于检索结果组织回复，是客服 Agent 里常见的 RAG 思路。
        if (intentRules.isKnowledgeRelatedMessage(lowerMessage)) {
            state.setActiveIntent(INTENT_KNOWLEDGE_QUERY);
            state.clearPendingSlot();
            return new CapabilityAction("knowledge_search", Map.of("query", message));
        }

        // 订单查询：支持中文和英文关键词。
        //
        // 注意：如果一句话同时包含“订单”和“退货”，例如“我想退这个订单 ORD-004”，
        // 它更应该走退货申请，而不是订单查询。
        // 所以这里加了 !isReturnRelatedMessage(lowerMessage)，避免退货类消息被订单查询规则提前截走。
        if (intentRules.containsAny(lowerMessage, "order", "logistics", "delivery", "订单", "物流", "快递", "发货", "到哪")
                && !intentRules.isReturnRelatedMessage(lowerMessage)) {
            // 如果用户问“我的订单”，通常是查当前用户的全部订单，不需要订单号。
            if (intentRules.containsAny(lowerMessage, "my order", "my orders", "我的订单", "全部订单", "查看我的订单", "有哪些订单")) {
                state.setActiveIntent("order_query");
                return new CapabilityAction("user_orders", Map.of("user_id", state.getUserId()));
            }

            // 如果用户提供了 ORD-001 这种订单号，就直接调用订单查询工具。
            String orderId = slotExtractor.extractOrderId(message);
            if (orderId != null) {
                state.setActiveIntent(INTENT_ORDER_QUERY);
                state.setTaskState(SLOT_ORDER_ID, orderId);
                state.clearPendingSlot();
                return new CapabilityAction("order_lookup", Map.of("order_id", orderId));
            }
            state.setActiveIntent(INTENT_ORDER_QUERY);
            state.setPendingSlot(SLOT_ORDER_ID);
            return new AskUserAction("请提供订单号，例如 ORD-001，我来帮您查询订单状态。");
        }

        // 商品咨询：包括价格、库存、推荐、具体品类等表达。
        if (intentRules.containsAny(lowerMessage, "product", "price", "recommend", "产品", "商品", "价格", "多少钱",
                "有货", "库存", "推荐", "耳机", "手表", "充电器", "数据线", "蓝牙", "无线")) {
            state.setActiveIntent("product_query");
            String keyword = resolveProductSearchKeyword(context);
            return new CapabilityAction("product_search", Map.of("keyword", keyword != null ? keyword : message));
        }

        // 退换货进度查询：需要退换货单号 RET-001。
        if (intentRules.containsAny(lowerMessage, "return status", "return progress", "退货进度", "退换货状态", "退换货进度")) {
            String returnId = slotExtractor.extractReturnId(message);
            if (returnId != null) {
                state.setActiveIntent(INTENT_RETURN_QUERY);
                state.setTaskState(SLOT_RETURN_ID, returnId);
                state.clearPendingSlot();
                return new CapabilityAction("return_query", Map.of("return_id", returnId));
            }
            state.setActiveIntent(INTENT_RETURN_QUERY);
            state.setPendingSlot(SLOT_RETURN_ID);
            return new AskUserAction("请提供退换货单号，例如 RET-001，我来帮您查询处理进度。");
        }

        // 申请退换货：第一版重点做成多轮闭环。
        //
        // 这个任务至少需要两个槽位：
        // 1. order_id：用户要退哪个订单
        // 2. return_reason：为什么要退
        //
        // 如果用户一次性说清楚，就直接调用工具；
        // 如果缺少某个信息，就记录 pending_slot，然后追问用户。
        if (intentRules.isReturnRelatedMessage(lowerMessage)) {
            state.setActiveIntent(INTENT_RETURN_APPLY);

            String orderId = slotExtractor.extractOrderId(message);
            if (orderId == null) {
                state.setPendingSlot(SLOT_ORDER_ID);
                return new AskUserAction("请提供需要退换货的订单号，例如 ORD-004。");
            }

            state.setTaskState(SLOT_ORDER_ID, orderId);

            String reason = slotExtractor.extractReturnReason(message, orderId);
            if (slotExtractor.hasText(reason)) {
                state.setTaskState(SLOT_RETURN_REASON, reason);
                state.clearPendingSlot();
                return buildReturnApplyAction(orderId, reason);
            }

            state.setPendingSlot(SLOT_RETURN_REASON);
            return new AskUserAction("已收到订单号 " + orderId + "。请简单说明退货原因，例如质量问题、不想要了或尺码不合适。");
        }

        // 用户信息查询：使用当前会话里的 userId 作为身份标识。
        if (intentRules.containsAny(lowerMessage, "my info", "profile", "account", "我的信息", "账号", "个人信息", "我是谁")) {
            state.setActiveIntent("user_info");
            return new CapabilityAction("user_lookup", Map.of("token", state.getUserId()));
        }

        // 闲聊不需要业务工具，直接让 LLM 生成友好的客服回复。
        if (intentRules.containsAny(lowerMessage, "hello", "hi", "thanks", "bye", "你好", "在吗", "谢谢", "再见")) {
            state.setActiveIntent("chitchat");
            return new LLMAction("response_generation", promptBuilder.buildChitchatPrompt(message), new HashMap<>());
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
    private AgentAction llmRoute(TurnContext context) {
        return new LLMAction("intent_recognition", promptBuilder.buildIntentRecognitionPrompt(context), new HashMap<>());
    }

    /**
     * 把 LLM 识别出的意图名称，转换成真正可执行的 Action。
     *
     * 例如：
     * - ORDER_QUERY + 有订单号 -> 调用 order_lookup
     * - ORDER_QUERY + 没订单号 -> 追问订单号
     * - CHITCHAT -> 让 LLM 直接生成回复
     */
    private AgentAction actionFromIntent(String intentResult, TurnContext context) {
        ConversationState state = context.getState();
        String message = context.getMessage();
        String intent = intentResult == null ? "" : intentResult.trim().toUpperCase();

        if (intent.contains("ORDER_QUERY")) {
            state.setActiveIntent(INTENT_ORDER_QUERY);
            String orderId = slotExtractor.extractOrderId(message);
            if (orderId != null) {
                state.setTaskState(SLOT_ORDER_ID, orderId);
                state.clearPendingSlot();
                return new CapabilityAction("order_lookup", Map.of("order_id", orderId));
            }
            if (intentRules.containsAny(message.toLowerCase(), "我的订单", "my orders", "all orders")) {
                return new CapabilityAction("user_orders", Map.of("user_id", state.getUserId()));
            }
            state.setPendingSlot(SLOT_ORDER_ID);
            return new AskUserAction("请提供订单号，例如 ORD-001，我来帮您查询订单状态。");
        }

        if (intent.contains("PRODUCT_QUERY")) {
            state.setActiveIntent("product_query");
            String keyword = resolveProductSearchKeyword(context);
            return new CapabilityAction("product_search", Map.of("keyword", keyword != null ? keyword : ""));
        }

        if (intent.contains("RETURN_QUERY")) {
            state.setActiveIntent(INTENT_RETURN_QUERY);
            String returnId = slotExtractor.extractReturnId(message);
            if (returnId != null) {
                state.setTaskState(SLOT_RETURN_ID, returnId);
                state.clearPendingSlot();
                return new CapabilityAction("return_query", Map.of("return_id", returnId));
            }
            state.setPendingSlot(SLOT_RETURN_ID);
            return new AskUserAction("请提供退换货单号，例如 RET-001，我来帮您查询处理进度。");
        }

        if (intent.contains("RETURN_APPLY")) {
            state.setActiveIntent(INTENT_RETURN_APPLY);
            String orderId = slotExtractor.extractOrderId(message);
            if (orderId == null) {
                state.setPendingSlot(SLOT_ORDER_ID);
                return new AskUserAction("请提供需要退换货的订单号，例如 ORD-004。");
            }

            state.setTaskState(SLOT_ORDER_ID, orderId);
            String reason = slotExtractor.extractReturnReason(message, orderId);
            if (slotExtractor.hasText(reason)) {
                state.setTaskState(SLOT_RETURN_REASON, reason);
                state.clearPendingSlot();
                return buildReturnApplyAction(orderId, reason);
            }

            state.setPendingSlot(SLOT_RETURN_REASON);
            return new AskUserAction("已收到订单号 " + orderId + "。请简单说明退货原因，例如质量问题、不想要了或尺码不合适。");
        }

        if (intent.contains("KNOWLEDGE_QUERY")) {
            state.setActiveIntent(INTENT_KNOWLEDGE_QUERY);
            state.clearPendingSlot();
            return new CapabilityAction("knowledge_search", Map.of("query", message));
        }

        if (intent.contains("HANDOFF")) {
            return buildHandoffAction(context, intentRules.classifyHandoffReason(message.toLowerCase()));
        }

        if (intent.contains("USER_INFO")) {
            state.setActiveIntent("user_info");
            return new CapabilityAction("user_lookup", Map.of("token", state.getUserId()));
        }

        if (intent.contains("CHITCHAT")) {
            state.setActiveIntent("chitchat");
            return new LLMAction("response_generation", promptBuilder.buildChitchatPrompt(message), new HashMap<>());
        }

        return null;
    }

    /**
     * 处理多轮任务中“正在等待用户补充的信息”。
     *
     * pending_slot 可以理解成一个书签：
     * - 它告诉 Agent：上一轮任务还没完成，当前正在等哪个字段。
     * - 用户下一轮的消息，不一定包含完整意图，但可以被当作这个字段的答案。
     */
    private AgentAction handlePendingSlot(TurnContext context) {
        ConversationState state = context.getState();
        String pendingSlot = state.getPendingSlot();
        if (pendingSlot == null) {
            return null;
        }

        String activeIntent = state.getActiveIntent();

        if (INTENT_ORDER_QUERY.equals(activeIntent)) {
            return handleOrderQueryPendingSlot(context, pendingSlot);
        }

        if (INTENT_RETURN_QUERY.equals(activeIntent)) {
            return handleReturnQueryPendingSlot(context, pendingSlot);
        }

        if (INTENT_RETURN_APPLY.equals(activeIntent)) {
            return handleReturnApplyPendingSlot(context, pendingSlot);
        }

        // 如果存在 pending_slot，但 activeIntent 不是我们认识的任务，
        // 先交回普通规则路由处理，避免系统被未知状态卡死。
        return null;
    }

    /**
     * 处理“查询订单”任务里缺失的订单号。
     */
    private AgentAction handleOrderQueryPendingSlot(TurnContext context, String pendingSlot) {
        ConversationState state = context.getState();
        if (!SLOT_ORDER_ID.equals(pendingSlot)) {
            return null;
        }

        String orderId = slotExtractor.extractOrderId(context.getMessage());
        if (orderId == null) {
            return new AskUserAction("我还没有识别到订单号，请提供类似 ORD-001 的订单号。");
        }

        state.setTaskState(SLOT_ORDER_ID, orderId);
        state.clearPendingSlot();
        return new CapabilityAction("order_lookup", Map.of("order_id", orderId));
    }

    /**
     * 处理“查询退换货进度”任务里缺失的退换货单号。
     */
    private AgentAction handleReturnQueryPendingSlot(TurnContext context, String pendingSlot) {
        ConversationState state = context.getState();
        if (!SLOT_RETURN_ID.equals(pendingSlot)) {
            return null;
        }

        String returnId = slotExtractor.extractReturnId(context.getMessage());
        if (returnId == null) {
            return new AskUserAction("我还没有识别到退换货单号，请提供类似 RET-001 的单号。");
        }

        state.setTaskState(SLOT_RETURN_ID, returnId);
        state.clearPendingSlot();
        return new CapabilityAction("return_query", Map.of("return_id", returnId));
    }

    /**
     * 处理“申请退货”任务中的槽位补全。
     *
     * 退货申请比查询类任务更能体现多轮状态：
     * - 第一步等订单号
     * - 第二步等退货原因
     * - 两个槽位都有了，才能真正调用 return_apply 工具
     */
    private AgentAction handleReturnApplyPendingSlot(TurnContext context, String pendingSlot) {
        ConversationState state = context.getState();
        String message = context.getMessage();

        if (SLOT_ORDER_ID.equals(pendingSlot)) {
            String orderId = slotExtractor.extractOrderId(message);
            if (orderId == null) {
                return new AskUserAction("我还没有识别到订单号，请提供类似 ORD-004 的订单号。");
            }

            state.setTaskState(SLOT_ORDER_ID, orderId);

            // 用户补充订单号时，也可能顺手把原因说了：
            // 例如“ORD-004，质量问题”。这种情况可以少追问一轮。
            String reason = slotExtractor.extractReturnReason(message, orderId);
            if (slotExtractor.hasText(reason)) {
                state.setTaskState(SLOT_RETURN_REASON, reason);
                state.clearPendingSlot();
                return buildReturnApplyAction(orderId, reason);
            }

            state.setPendingSlot(SLOT_RETURN_REASON);
            return new AskUserAction("已收到订单号 " + orderId + "。请简单说明退货原因，例如质量问题、不想要了或尺码不合适。");
        }

        if (SLOT_RETURN_REASON.equals(pendingSlot)) {
            Object orderIdValue = state.getTaskState(SLOT_ORDER_ID);
            if (orderIdValue == null) {
                // 正常流程不会走到这里。
                // 这里做兜底，是为了防止状态异常时直接调用工具导致参数缺失。
                state.setPendingSlot(SLOT_ORDER_ID);
                return new AskUserAction("我需要先确认需要退换货的订单号，请提供类似 ORD-004 的订单号。");
            }

            String orderId = orderIdValue.toString();
            String reason = slotExtractor.extractReturnReason(message, orderId);
            if (!slotExtractor.hasText(reason)) {
                return new AskUserAction("请简单说明退货原因，例如质量问题、不想要了或尺码不合适。");
            }

            state.setTaskState(SLOT_RETURN_REASON, reason);
            state.clearPendingSlot();
            return buildReturnApplyAction(orderId, reason);
        }

        return null;
    }

    /**
     * 构造退货申请工具调用。
     *
     * 单独抽出这个方法，是为了避免在规则路由、LLM 意图兜底、多轮槽位处理里重复写参数 Map。
     */
    private AgentAction buildReturnApplyAction(String orderId, String reason) {
        return new CapabilityAction("return_apply", Map.of(
                "order_id", orderId,
                "reason", reason
        ));
    }

    /**
     * 根据工具结果生成客服回复。
     *
     * 工具结果通常比较“系统化”，比如包含字段、英文状态等。
     * 这里交给 LLM 做一次润色，让最终回复更像真实客服。
     */
    private AgentAction generateResponseFromToolResult(TurnContext context) {
        return new LLMAction("response_generation", promptBuilder.buildResponseGenerationPrompt(context), new HashMap<>());
    }

    /**
     * 处理用户长期偏好写入。
     *
     * 第一版只记商品偏好，例如“我喜欢蓝牙耳机”。
     * 这样后续用户说“给我推荐一下”时，Agent 可以优先推荐偏好的品类。
     */
    private AgentAction handleUserMemory(TurnContext context) {
        ConversationState state = context.getState();
        String lowerMessage = context.getMessage().toLowerCase();
        if (!intentRules.isPreferenceMemoryMessage(lowerMessage)) {
            return null;
        }

        String preference = slotExtractor.extractProductPreference(context.getMessage());
        if (!slotExtractor.hasText(preference)) {
            return null;
        }

        UserMemoryWriteResult writeResult = userMemoryStore.rememberProductPreferenceSafely(
                state.getUserId(),
                preference
        );
        if (!writeResult.accepted()) {
            return new FinalAnswerAction(
                    "为保护隐私，我不会把手机号、邮箱、证件号或凭据保存为偏好。请只告诉我商品或品类。"
            );
        }

        String storedPreference = writeResult.storedPreference();
        UserMemorySnapshot snapshot = userMemoryStore.getSnapshot(state.getUserId());

        Map<String, Object> traceData = new LinkedHashMap<>();
        traceData.put("userId", state.getUserId());
        traceData.put("preference", storedPreference);
        traceData.put("added", writeResult.added());
        traceData.put("allProductPreferences", snapshot.getProductPreferences());
        context.addTrace("user_memory_updated", "记录用户长期商品偏好", traceData);

        state.setTaskState("last_user_preference", storedPreference);
        state.setActiveIntent("user_memory_update");
        return new FinalAnswerAction("好的，我记住了：你比较喜欢“" + storedPreference + "”。之后你让我推荐商品时，我会优先参考这个偏好。");
    }

    /**
     * 决定商品搜索关键词。
     *
     * 如果用户明确说了“蓝牙耳机”，直接使用当前消息里的关键词；
     * 如果用户只是说“推荐一下”，就尝试读取长期记忆里的商品偏好。
     */
    private String resolveProductSearchKeyword(TurnContext context) {
        ConversationState state = context.getState();
        String keyword = slotExtractor.extractProductKeyword(context.getMessage());
        if (slotExtractor.hasText(keyword)) {
            state.setTaskState("used_user_preference", null);
            return keyword;
        }

        UserMemorySnapshot snapshot = userMemoryStore.getSnapshot(state.getUserId());
        if (snapshot.hasProductPreferences()) {
            String preference = snapshot.getProductPreferences().get(snapshot.getProductPreferences().size() - 1);
            state.setTaskState("used_user_preference", preference);

            Map<String, Object> traceData = new LinkedHashMap<>();
            traceData.put("userId", state.getUserId());
            traceData.put("preference", preference);
            traceData.put("allProductPreferences", snapshot.getProductPreferences());
            context.addTrace("user_memory_used", "商品推荐使用用户长期偏好", traceData);
            return preference;
        }

        state.setTaskState("used_user_preference", null);
        return keyword;
    }

    /**
     * 转人工路由。
     *
     * 它单独从 ruleRoute 里拆出来，是为了让它可以在多轮槽位填充前执行。
     * 用户一旦明确要求人工、投诉或涉及赔偿纠纷，自动客服就应该收敛边界。
     */
    private AgentAction handoffRoute(TurnContext context) {
        String lowerMessage = context.getMessage().toLowerCase();
        if (!intentRules.shouldHandoff(lowerMessage)) {
            return null;
        }
        return buildHandoffAction(context, intentRules.classifyHandoffReason(lowerMessage));
    }

    private HandoffAction buildHandoffAction(TurnContext context, String reason) {
        ConversationState state = context.getState();
        String previousIntent = state.getActiveIntent();
        state.setActiveIntent(INTENT_HANDOFF);
        state.clearPendingSlot();

        Map<String, Object> handoffContext = new LinkedHashMap<>();
        handoffContext.put("user_id", state.getUserId());
        handoffContext.put("active_intent", state.getActiveIntent());
        if (previousIntent != null) {
            handoffContext.put("previous_intent", previousIntent);
        }

        Object orderId = state.getTaskState(SLOT_ORDER_ID);
        if (orderId != null) {
            handoffContext.put("order_id", orderId);
        }

        String summary = buildHandoffSummary(context, reason, previousIntent);
        return new HandoffAction(reason, summary, handoffContext);
    }

    /**
     * 给人工客服看的摘要。
     *
     * 第一版先用规则拼接，后续可以升级成 LLM 总结：
     * - 用户当前诉求
     * - 当前会话意图
     * - 已收集到的订单号等槽位
     * - 转人工原因
     */
    private String buildHandoffSummary(TurnContext context, String reason, String previousIntent) {
        ConversationState state = context.getState();
        StringBuilder summary = new StringBuilder();

        summary.append("用户当前消息：").append(context.getMessage());
        summary.append("；转人工原因：").append(reason);

        if (previousIntent != null) {
            summary.append("；转人工前意图：").append(previousIntent);
        }

        Object orderId = state.getTaskState(SLOT_ORDER_ID);
        if (orderId != null) {
            summary.append("；已识别订单号：").append(orderId);
        }

        Object returnId = state.getTaskState(SLOT_RETURN_ID);
        if (returnId != null) {
            summary.append("；已识别退换货单号：").append(returnId);
        }

        return summary.toString();
    }

}
