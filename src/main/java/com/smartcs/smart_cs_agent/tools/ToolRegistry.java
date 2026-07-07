package com.smartcs.smart_cs_agent.tools;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.smartcs.smart_cs_agent.tools.ToolParameterDefinition.stringParam;

/**
 * 工具注册表。
 *
 * 【为什么需要 ToolRegistry？】
 * 旧版 ToolManager 用 switch 根据字符串调用工具：
 *
 * order_lookup -> orderTools.queryOrderStatus(...)
 *
 * 这种写法简单，但工具越来越多时会有几个问题：
 * - 所有工具分发逻辑挤在一个 switch 里
 * - 工具参数缺失时容易出现空指针
 * - 工具没有统一定义，无法“列出当前系统支持哪些工具”
 * - 后续迁移 MCP 时缺少标准边界
 *
 * ToolRegistry 的职责是：
 * - 注册工具定义 ToolDefinition
 * - 绑定工具执行逻辑 ToolHandler
 * - 执行前做基础参数校验
 * - 所有工具统一返回 ToolResult
 *
 * 这一步还不是完整 MCP Server，但已经具备 MCP-style 的工具注册和调用模型。
 */
@Component
public class ToolRegistry {

    /** 工具名 -> 注册工具 */
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    public ToolRegistry(OrderTools orderTools,
                        ProductTools productTools,
                        ReturnTools returnTools,
                        UserTools userTools,
                        KnowledgeTools knowledgeTools,
                        HandoffTools handoffTools) {
        registerOrderTools(orderTools);
        registerProductTools(productTools);
        registerReturnTools(returnTools);
        registerUserTools(userTools);
        registerKnowledgeTools(knowledgeTools);
        registerHandoffTools(handoffTools);
    }

    public List<ToolDefinition> listTools() {
        return tools.values()
                .stream()
                .map(RegisteredTool::definition)
                .toList();
    }

    public ToolResult execute(ToolCall call) {
        RegisteredTool registeredTool = tools.get(call.getName());
        if (registeredTool == null) {
            return ToolResult.failure(
                    call.getName(),
                    "TOOL_NOT_FOUND",
                    "Unknown capability: " + call.getName()
            );
        }

        List<String> missingParameters = findMissingParameters(registeredTool.definition(), call.getArguments());
        if (!missingParameters.isEmpty()) {
            return ToolResult.failure(
                    call.getName(),
                    "MISSING_ARGUMENT",
                    "Missing required argument(s): " + String.join(", ", missingParameters)
            );
        }

        try {
            return registeredTool.handler().execute(call);
        } catch (RuntimeException e) {
            // 这里不把异常直接抛给 Runtime，而是转成结构化失败结果。
            // 这样 Agent 后续可以根据 ToolResult 生成友好的客服回复。
            return ToolResult.failure(
                    call.getName(),
                    "TOOL_EXECUTION_ERROR",
                    String.valueOf(e.getMessage())
            );
        }
    }

    private void registerOrderTools(OrderTools orderTools) {
        register(
                new ToolDefinition(
                        "order_lookup",
                        "查询指定订单的状态、商品和物流信息",
                        List.of(stringParam("order_id", "订单号", true, "ORD-001")),
                        "OrderToolData",
                        List.of(
                                outputField("found", "boolean", "是否找到订单", "true"),
                                outputField("order_id", "string", "订单号", "ORD-001"),
                                outputField("user_id", "string", "订单所属用户 ID", "USER-001"),
                                outputField("product_name", "string", "商品名称", "蓝牙耳机 Pro Max"),
                                outputField("status", "string", "订单状态", "已发货"),
                                outputField("tracking_no", "string", "物流单号", "SF1234567890")
                        )
                ),
                call -> {
                    String orderId = stringArg(call, "order_id");
                    String result = orderTools.queryOrderStatus(orderId);
                    return ToolResult.success(call.getName(), result, orderTools.queryOrderStatusData(orderId));
                }
        );

        register(
                new ToolDefinition(
                        "user_orders",
                        "查询指定用户的全部订单",
                        List.of(stringParam("user_id", "用户 ID", true, "USER-001")),
                        "UserOrdersToolData",
                        List.of(
                                outputField("user_id", "string", "用户 ID", "USER-001"),
                                outputField("count", "number", "订单数量", "2"),
                                outputField("orders", "array", "订单列表，元素结构为 OrderToolData", "[]")
                        )
                ),
                call -> {
                    String userId = stringArg(call, "user_id");
                    String result = orderTools.queryUserOrders(userId);
                    return ToolResult.success(call.getName(), result, orderTools.queryUserOrdersData(userId));
                }
        );
    }

    private void registerProductTools(ProductTools productTools) {
        register(
                new ToolDefinition(
                        "product_search",
                        "根据关键词搜索商品列表",
                        List.of(stringParam("keyword", "商品关键词，可以为空字符串表示搜索全部商品", false, "蓝牙")),
                        "ProductSearchToolData",
                        List.of(
                                outputField("keyword", "string", "本次搜索关键词", "蓝牙"),
                                outputField("count", "number", "命中的商品数量", "1"),
                                outputField("products", "array", "商品列表，元素结构为 ProductItemData", "[]")
                        )
                ),
                call -> {
                    Object keywordValue = call.getArguments().getOrDefault("keyword", "");
                    String keyword = keywordValue == null ? "" : keywordValue.toString();
                    String result = productTools.searchProducts(keyword);
                    return ToolResult.success(call.getName(), result, productTools.searchProductsData(keyword));
                }
        );

        register(
                new ToolDefinition(
                        "product_info",
                        "查询单个商品的详细信息",
                        List.of(stringParam("product_name", "商品名称或关键词", true, "蓝牙耳机")),
                        "ProductInfoToolData",
                        List.of(
                                outputField("query", "string", "查询关键词", "蓝牙"),
                                outputField("matched_count", "number", "匹配数量", "1"),
                                outputField("found", "boolean", "是否找到商品", "true"),
                                outputField("product", "object", "商品详情，结构为 ProductItemData", "{}")
                        )
                ),
                call -> {
                    String productName = stringArg(call, "product_name");
                    String result = productTools.queryProductInfo(productName);
                    return ToolResult.success(call.getName(), result, productTools.queryProductInfoData(productName));
                }
        );
    }

    private void registerReturnTools(ReturnTools returnTools) {
        register(
                new ToolDefinition(
                        "return_apply",
                        "提交退换货申请",
                        List.of(
                                stringParam("order_id", "订单号", true, "ORD-004"),
                                stringParam("reason", "退换货原因", true, "质量问题")
                        ),
                        "ReturnToolData",
                        List.of(
                                outputField("found", "boolean", "订单或退货记录是否存在", "true"),
                                outputField("submitted", "boolean", "是否成功提交退货申请", "true"),
                                outputField("reason_code", "string", "失败或特殊状态原因码", "ORDER_NOT_FOUND"),
                                outputField("return_id", "string", "退换货单号", "RET-002"),
                                outputField("order_id", "string", "订单号", "ORD-004"),
                                outputField("product_name", "string", "商品名称", "智能手表 S1"),
                                outputField("reason", "string", "退换货原因", "质量问题"),
                                outputField("status", "string", "退换货状态", "Under Review"),
                                outputField("expected_review_time", "string", "预计审核时间", "1-2 business days")
                        )
                ),
                call -> {
                    String orderId = stringArg(call, "order_id");
                    String reason = stringArg(call, "reason");
                    StructuredToolOutput output = returnTools.applyReturnStructured(orderId, reason);
                    return ToolResult.success(call.getName(), output.getMessage(), output.getData());
                }
        );

        register(
                new ToolDefinition(
                        "return_query",
                        "查询退换货申请状态",
                        List.of(stringParam("return_id", "退换货单号", true, "RET-001")),
                        "ReturnToolData",
                        List.of(
                                outputField("found", "boolean", "是否找到退换货记录", "true"),
                                outputField("return_id", "string", "退换货单号", "RET-001"),
                                outputField("order_id", "string", "订单号", "ORD-003"),
                                outputField("product_name", "string", "商品名称", "USB-C 数据线"),
                                outputField("reason", "string", "退换货原因", "质量问题"),
                                outputField("status", "string", "退换货状态", "Approved")
                        )
                ),
                call -> {
                    String returnId = stringArg(call, "return_id");
                    String result = returnTools.queryReturnStatus(returnId);
                    return ToolResult.success(call.getName(), result, returnTools.queryReturnStatusData(returnId));
                }
        );
    }

    private void registerUserTools(UserTools userTools) {
        register(
                new ToolDefinition(
                        "user_lookup",
                        "查询用户基础信息",
                        List.of(
                                stringParam("token", "用户登录 token", false, "USER-001"),
                                stringParam("user_id", "用户 ID，当前演示环境兼容 user_id", false, "USER-001")
                        ),
                        "UserToolData",
                        List.of(
                                outputField("found", "boolean", "是否找到用户", "true"),
                                outputField("user_id", "string", "用户 ID", "USER-001"),
                                outputField("name", "string", "用户姓名", "张三"),
                                outputField("phone", "string", "手机号", "13800000001")
                        )
                ),
                call -> {
                    Object token = call.getArguments().getOrDefault("token", call.getArguments().get("user_id"));
                    if (token == null || token.toString().isBlank()) {
                        return ToolResult.failure(
                                call.getName(),
                                "MISSING_ARGUMENT",
                                "Missing required argument: token or user_id"
                        );
                    }

                    String result = userTools.queryUserInfo(token.toString());
                    return ToolResult.success(call.getName(), result, userTools.queryUserInfoData(token.toString()));
                }
        );
    }

    private void registerKnowledgeTools(KnowledgeTools knowledgeTools) {
        register(
                new ToolDefinition(
                        "knowledge_search",
                        "检索客服知识库，用于回答退换货政策、保修、发票、配送、转人工等规则类问题",
                        List.of(stringParam("query", "用户提出的政策或 FAQ 问题", true, "超过七天还能退吗")),
                        "KnowledgeSearchResult[]",
                        List.of(
                                outputField("document_id", "string", "命中的知识文档 ID", "policy/return_policy.md"),
                                outputField("category", "string", "知识分类", "policy"),
                                outputField("title", "string", "文档标题", "退换货政策"),
                                outputField("score", "number", "检索分数", "8"),
                                outputField("snippet", "string", "命中的知识片段", "超过 7 天后，不再默认支持七天无理由退货"),
                                outputField("matchedTerms", "array", "本次检索命中的关键词", "[\"退货\", \"七天\"]"),
                                outputField("matchedFields", "array", "命中的字段，例如 title、keywords、content", "[\"keywords\", \"content\"]"),
                                outputField("queryVariants", "array", "查询改写后的候选表达", "[\"超过七天还能退吗\", \"退货 退款 七天无理由\"]"),
                                outputField("rerankReason", "string", "排序原因，便于解释 rerank 结果", "title=0, keywords=20, content=8, fieldCoverageBonus=4")
                        )
                ),
                call -> {
                    String query = stringArg(call, "query");
                    List<KnowledgeSearchResult> results = knowledgeTools.search(query, 3);
                    String message = knowledgeTools.formatSearchResult(query, results);
                    return ToolResult.success(call.getName(), message, results);
                }
        );
    }

    private void registerHandoffTools(HandoffTools handoffTools) {
        register(
                new ToolDefinition(
                        "handoff_create",
                        "创建人工转接工单，用于用户要求人工、强投诉、赔偿纠纷等自动客服不应继续处理的场景",
                        List.of(
                                stringParam("user_id", "用户 ID", true, "USER-001"),
                                stringParam("conversation_id", "会话 ID", true, "conv-001"),
                                stringParam("reason", "转人工原因", true, "COMPLAINT"),
                                stringParam("summary", "给人工客服看的问题摘要", true, "用户强烈投诉，需要人工处理")
                        ),
                        "HandoffTicketData",
                        List.of(
                                outputField("ticket_id", "string", "人工工单号", "HD-20260706232800123"),
                                outputField("user_id", "string", "用户 ID", "USER-001"),
                                outputField("conversation_id", "string", "会话 ID", "conv-001"),
                                outputField("reason", "string", "转人工原因", "COMPLAINT"),
                                outputField("summary", "string", "人工客服处理摘要", "用户强烈投诉，需要人工处理"),
                                outputField("status", "string", "工单状态", "WAITING_HUMAN_AGENT"),
                                outputField("created_at", "string", "创建时间", "2026-07-06T23:28:00")
                        )
                ),
                call -> {
                    String userId = stringArg(call, "user_id");
                    String conversationId = stringArg(call, "conversation_id");
                    String reason = stringArg(call, "reason");
                    String summary = stringArg(call, "summary");

                    HandoffTicketData ticket = handoffTools.createHandoffTicket(
                            userId,
                            conversationId,
                            reason,
                            summary
                    );
                    return ToolResult.success(call.getName(), handoffTools.formatTicket(ticket), ticket);
                }
        );
    }

    private void register(ToolDefinition definition, ToolHandler handler) {
        tools.put(definition.getName(), new RegisteredTool(definition, handler));
    }

    private List<String> findMissingParameters(ToolDefinition definition, Map<String, Object> arguments) {
        List<String> missingParameters = new ArrayList<>();

        for (String parameterName : definition.getRequiredParameters()) {
            Object value = arguments.get(parameterName);
            if (value == null || value.toString().isBlank()) {
                missingParameters.add(parameterName);
            }
        }

        return missingParameters;
    }

    private String stringArg(ToolCall call, String key) {
        Object value = call.getArguments().get(key);
        return value == null ? null : value.toString();
    }

    private static ToolParameterDefinition outputField(String name,
                                                       String type,
                                                       String description,
                                                       String example) {
        // 输出字段不参与执行前参数校验，所以 required 统一设为 false。
        return new ToolParameterDefinition(name, type, description, false, example);
    }

    /**
     * 注册后的工具实体。
     *
     * Java record 适合表达这种“只有字段、不需要复杂行为”的小对象。
     */
    private record RegisteredTool(ToolDefinition definition, ToolHandler handler) {
    }
}
