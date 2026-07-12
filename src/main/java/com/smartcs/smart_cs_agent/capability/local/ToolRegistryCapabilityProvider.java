package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderListResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolDefinition;
import com.smartcs.smart_cs_agent.tools.ToolRegistry;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import com.smartcs.smart_cs_agent.tools.OrderToolData;
import com.smartcs.smart_cs_agent.tools.ProductInfoToolData;
import com.smartcs.smart_cs_agent.tools.ReturnToolData;
import com.smartcs.smart_cs_agent.tools.UserToolData;
import com.smartcs.smart_cs_agent.tools.UserOrdersToolData;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将现有 ToolRegistry 暂时包装成本地 CapabilityProvider。
 *
 * <p>调用链过渡为：AgentRuntime -> ToolManager -> CapabilityGateway ->
 * ToolRegistryCapabilityProvider -> ToolRegistry。这样可以先让新 Gateway 真实进入生产链路，
 * 再逐步把具体 Tool 迁移为独立 Capability Handler。</p>
 *
 * <p>该 Adapter 不新增业务规则。它已把订单/商品/退货/用户不存在以及缺参数映射为业务拒绝；
 * 其余无法识别的旧失败仍保守映射为 TECHNICAL_FAILURE。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class ToolRegistryCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-tool-registry";

    private final ToolRegistry toolRegistry;
    private final boolean includeLegacyReturnApply;
    private final boolean includeLegacyReturnLookup;

    @Autowired
    public ToolRegistryCapabilityProvider(ToolRegistry toolRegistry,
                                          LocalReturnLookupCapabilityProvider ignoredMigratedProvider) {
        this(toolRegistry, false, false);
    }

    /** 手动装配兼容入口：没有独立 Provider 时继续暴露旧 return.lookup。 */
    public ToolRegistryCapabilityProvider(ToolRegistry toolRegistry) {
        this(toolRegistry, false, true);
    }

    /**
     * includeLegacyReturnApply 只供不启动 Spring 的旧测试装配使用。
     * 生产环境固定为 false，由 LocalReturnApplyCapabilityProvider 接管安全写入。
     */
    public ToolRegistryCapabilityProvider(ToolRegistry toolRegistry, boolean includeLegacyReturnApply) {
        this(toolRegistry, includeLegacyReturnApply, true);
    }

    private ToolRegistryCapabilityProvider(ToolRegistry toolRegistry,
                                           boolean includeLegacyReturnApply,
                                           boolean includeLegacyReturnLookup) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.includeLegacyReturnApply = includeLegacyReturnApply;
        this.includeLegacyReturnLookup = includeLegacyReturnLookup;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        return toolRegistry.listTools().stream()
                // return.apply 已迁移为独立 LocalReturnApplyCapabilityProvider，不能重复注册。
                .filter(definition -> includeLegacyReturnApply || !"return_apply".equals(definition.getName()))
                // Spring 生产链路由 LocalReturnLookupCapabilityProvider 接管安全只读查询。
                .filter(definition -> includeLegacyReturnLookup || !"return_query".equals(definition.getName()))
                .map(this::toCapabilityDefinition)
                .toList();
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String canonicalId = CapabilityIds.canonicalize(call.getCapabilityId());
        CapabilityCall securedCall = injectTrustedArguments(canonicalId, call, context);
        ToolResult legacyResult = toolRegistry.execute(
                new ToolCall(CapabilityIds.legacyName(canonicalId), securedCall.getArguments())
        );

        if (legacyResult.isSuccess()) {
            return normalizeSuccessfulLegacyResult(canonicalId, legacyResult, context);
        }

        String errorCode = legacyResult.getErrorCode() == null
                ? "LEGACY_TOOL_FAILURE"
                : legacyResult.getErrorCode();
        if ("MISSING_ARGUMENT".equals(errorCode)) {
            return CapabilityResult.businessRejected(
                    canonicalId,
                    PROVIDER_ID,
                    errorCode,
                    legacyResult.getMessage(),
                    null
            );
        }
        return CapabilityResult.technicalFailure(canonicalId, PROVIDER_ID, errorCode, legacyResult.getMessage());
    }

    private CapabilityDefinition toCapabilityDefinition(ToolDefinition definition) {
        return LegacyCapabilityContractMapper.fromLegacy(definition);
    }

    /** 把“成功返回但业务对象不存在”从技术成功纠正为 BUSINESS_REJECTED。 */
    private CapabilityResult<?> normalizeSuccessfulLegacyResult(String capabilityId,
                                                                ToolResult result,
                                                                ExecutionContext context) {
        Object data = result.getData();

        // 这是“旧 Tool 输出 -> 新 Capability 输出契约”的 Anti-Corruption Layer。
        // Workflow 不应该知道 KnowledgeSearchResult 位于旧 tools 包中。
        if (CapabilityIds.KNOWLEDGE_SEARCH.equals(capabilityId) && data instanceof List<?> rows) {
            List<KnowledgeSearchHit> hits = rows.stream()
                    .filter(KnowledgeSearchResult.class::isInstance)
                    .map(KnowledgeSearchResult.class::cast)
                    .map(hit -> new KnowledgeSearchHit(
                            hit.getDocumentId(),
                            hit.getCategory(),
                            hit.getTitle(),
                            hit.getScore(),
                            hit.getSnippet()
                    ))
                    .toList();
            return CapabilityResult.success(capabilityId, PROVIDER_ID, result.getMessage(), hits);
        }
        if (data instanceof OrderToolData order) {
            if (!order.isFound()) {
                return businessRejected(capabilityId, "ORDER_NOT_FOUND", result);
            }

            // 订单号不是访问凭证。即使用户猜到了其他人的订单号，也不能返回商品和物流详情。
            String principalUserId = context.getPrincipal().getUserId();
            if (!principalUserId.isBlank() && !principalUserId.equals(order.getUserId())) {
                return CapabilityResult.businessRejected(
                        capabilityId,
                        PROVIDER_ID,
                        "ORDER_OWNERSHIP_MISMATCH",
                        "The order does not belong to the authenticated user",
                        null
                );
            }
            return CapabilityResult.success(
                    capabilityId,
                    PROVIDER_ID,
                    result.getMessage(),
                    toOrderSummary(order)
            );
        }
        if (data instanceof UserOrdersToolData userOrders) {
            List<OrderSummary> orders = userOrders.getOrders().stream()
                    .map(this::toOrderSummary)
                    .toList();
            return CapabilityResult.success(
                    capabilityId,
                    PROVIDER_ID,
                    result.getMessage(),
                    new OrderListResult(userOrders.getUserId(), orders)
            );
        }
        if (data instanceof ProductInfoToolData product && !product.isFound()) {
            return businessRejected(capabilityId, "PRODUCT_NOT_FOUND", result);
        }
        if (data instanceof ReturnToolData returnData && !returnData.isFound()) {
            return businessRejected(capabilityId, "RETURN_NOT_FOUND", result);
        }
        if (data instanceof UserToolData user && !user.isFound()) {
            return businessRejected(capabilityId, "USER_NOT_FOUND", result);
        }
        return CapabilityResult.success(capabilityId, PROVIDER_ID, result.getMessage(), data);
    }

    /**
     * 对涉及身份的旧工具参数做服务端覆盖。
     *
     * <p>order.list-by-user 的 user_id 不能由 LLM 或外部 MCP 参数决定。Gateway 已验证 Principal
     * 至少包含身份；这里把可信 userId 写入旧 ToolRegistry 需要的兼容参数。</p>
     */
    private CapabilityCall injectTrustedArguments(String capabilityId,
                                                  CapabilityCall call,
                                                  ExecutionContext context) {
        if (!CapabilityIds.ORDER_LIST_BY_USER.equals(capabilityId)) {
            return call;
        }
        String principalUserId = context.getPrincipal().getUserId();
        if (principalUserId.isBlank()) {
            return call;
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.getArguments());
        arguments.put("user_id", principalUserId);
        return new CapabilityCall(capabilityId, arguments);
    }

    private OrderSummary toOrderSummary(OrderToolData order) {
        return new OrderSummary(
                order.getOrderId(),
                order.getUserId(),
                order.getProductId(),
                order.getProductName(),
                order.getStatus(),
                order.getTrackingNo()
        );
    }

    private CapabilityResult<?> businessRejected(String capabilityId, String errorCode, ToolResult result) {
        return CapabilityResult.businessRejected(
                capabilityId,
                PROVIDER_ID,
                errorCode,
                result.getMessage(),
                result.getData()
        );
    }
}
