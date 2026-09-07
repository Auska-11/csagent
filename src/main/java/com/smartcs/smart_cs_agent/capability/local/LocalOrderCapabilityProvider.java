package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderListResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 订单查询的独立本地 Capability Provider。
 *
 * <p>订单号不是访问凭证。查询单个订单时，本类会在返回数据前校验订单归属；查询订单列表时，
 * 它完全忽略 arguments 中可能伪造的 user_id，只使用服务端认证 Principal。这样无论调用来自
 * Agent、REST 适配器还是 MCP Server，都经过同一条权限边界。</p>
 *
 * <p>Provider 只返回 capability.api.contracts 中的稳定 DTO，不再经过 OrderTools、ToolRegistry 或
 * 旧 ToolResult。Workflow 因此不需要知道数据来自内存仓库、MySQL 还是未来的远程订单服务。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalOrderCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-order";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final List<CapabilityDefinition> definitions;

    public LocalOrderCapabilityProvider(OrderRepository orderRepository,
                                        ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.definitions = List.of(lookupDefinition(), listDefinition());
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        return definitions;
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String capabilityId = CapabilityIds.canonicalize(call.getCapabilityId());
        if (CapabilityIds.ORDER_LOOKUP.equals(capabilityId)) {
            return lookup(call, context);
        }
        if (CapabilityIds.ORDER_LIST_BY_USER.equals(capabilityId)) {
            return listForAuthenticatedUser(context);
        }
        return CapabilityResult.technicalFailure(
                capabilityId,
                PROVIDER_ID,
                "CAPABILITY_NOT_SUPPORTED",
                "Order provider does not support this capability"
        );
    }

    private CapabilityResult<?> lookup(CapabilityCall call, ExecutionContext context) {
        String orderId = textArgument(call, "order_id");
        if (orderId.isBlank()) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.ORDER_LOOKUP,
                    PROVIDER_ID,
                    "MISSING_ARGUMENT",
                    "order_id is required",
                    null
            );
        }

        Order order = orderRepository.findByOrderId(orderId.toUpperCase());
        if (order == null) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.ORDER_LOOKUP,
                    PROVIDER_ID,
                    "ORDER_NOT_FOUND",
                    "Order was not found",
                    null
            );
        }

        String authenticatedUserId = context.getPrincipal().getUserId();
        // 归属校验同时匹配租户和用户：订单号不是访问凭证，不同租户的同名用户也不能互相查单。
        if (!authenticatedUserId.equals(order.getUserId())
                || !tenantOf(context).equals(order.getTenantId())) {
            // 归属失败时 data 必须为 null，不能在错误响应里泄露商品、状态或物流号。
            return CapabilityResult.businessRejected(
                    CapabilityIds.ORDER_LOOKUP,
                    PROVIDER_ID,
                    "ORDER_OWNERSHIP_MISMATCH",
                    "The order does not belong to the authenticated user",
                    null
            );
        }

        return CapabilityResult.success(
                CapabilityIds.ORDER_LOOKUP,
                PROVIDER_ID,
                "Order " + order.getOrderId() + " found",
                toSummary(order)
        );
    }

    private CapabilityResult<OrderListResult> listForAuthenticatedUser(ExecutionContext context) {
        String authenticatedUserId = context.getPrincipal().getUserId();
        List<OrderSummary> orders = orderRepository
                .findByTenantIdAndUserId(tenantOf(context), authenticatedUserId).stream()
                .map(this::toSummary)
                .toList();
        return CapabilityResult.success(
                CapabilityIds.ORDER_LIST_BY_USER,
                PROVIDER_ID,
                "Found " + orders.size() + " orders for the authenticated user",
                new OrderListResult(authenticatedUserId, orders)
        );
    }

    private OrderSummary toSummary(Order order) {
        Product product = productRepository.findByProductId(order.getProductId());
        return new OrderSummary(
                order.getOrderId(),
                order.getUserId(),
                order.getProductId(),
                product == null ? "Unknown Product" : product.getName(),
                order.getStatus(),
                order.getTrackingNo()
        );
    }

    private String textArgument(CapabilityCall call, String key) {
        Object value = call.getArguments().get(key);
        return value == null ? "" : value.toString().trim();
    }

    /**
     * 租户以服务端认证 Principal 为准：它是 JWT 校验后的可信字段，不能读取可能被误传的
     * 请求元数据。未携带租户的 legacy/test 上下文按演示默认租户处理。
     */
    private String tenantOf(ExecutionContext context) {
        String tenantId = context.getPrincipal().getTenantId();
        return tenantId == null || tenantId.isBlank() ? Order.DEFAULT_TENANT_ID : tenantId.trim();
    }

    private CapabilityDefinition lookupDefinition() {
        return new CapabilityDefinition(
                CapabilityIds.ORDER_LOOKUP,
                "1.0.0",
                "查询当前认证用户自己的订单状态、商品和物流信息",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "order_id", Map.of("type", "string", "description", "订单号，例如 ORD-000")
                        ),
                        "required", List.of("order_id")
                ),
                orderOutputSchema(),
                true,
                true,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }

    private CapabilityDefinition listDefinition() {
        return new CapabilityDefinition(
                CapabilityIds.ORDER_LIST_BY_USER,
                "1.0.0",
                "列出当前认证用户自己的全部订单",
                Map.of("type", "object", "properties", Map.of()),
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "user_id", Map.of("type", "string"),
                                "count", Map.of("type", "integer"),
                                "orders", Map.of("type", "array", "items", orderOutputSchema())
                        )
                ),
                true,
                true,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }

    private Map<String, Object> orderOutputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "order_id", Map.of("type", "string"),
                        "user_id", Map.of("type", "string"),
                        "product_id", Map.of("type", "string"),
                        "product_name", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "tracking_no", Map.of("type", "string")
                )
        );
    }
}
