package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnStatusSummary;
import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 本地退货进度查询 Provider。
 *
 * <p>职责：按退货单号查询记录、沿 orderId 校验订单归属，并返回 ReturnStatusSummary。</p>
 *
 * <p>不负责：不决定什么时候查询、不向用户追问退货单号，也不组织客服回复。</p>
 *
 * <p>为什么独立于 ToolRegistry：退货记录本身没有 userId，权限校验必须关联 OrderRepository。
 * 把规则放在独立 Provider 后，无论调用来自 Agent 还是 MCP，都不能绕过同一归属检查。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalReturnLookupCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-return-lookup";

    private final ReturnRepository returnRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public LocalReturnLookupCapabilityProvider(ReturnRepository returnRepository,
                                               OrderRepository orderRepository,
                                               ProductRepository productRepository) {
        this.returnRepository = returnRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "return_id", Map.of(
                                "type", "string",
                                "description", "退货单号，例如 RET-001"
                        )
                ),
                "required", List.of("return_id")
        );
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "return_id", Map.of("type", "string"),
                        "order_id", Map.of("type", "string"),
                        "product_name", Map.of("type", "string"),
                        "reason", Map.of("type", "string"),
                        "status", Map.of("type", "string")
                )
        );
        return List.of(new CapabilityDefinition(
                CapabilityIds.RETURN_LOOKUP,
                "1.0.0",
                "查询当前认证用户自己的退货申请状态",
                inputSchema,
                outputSchema,
                true,
                true,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        ));
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String returnId = textArgument(call, "return_id");
        if (returnId.isBlank()) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.RETURN_LOOKUP,
                    PROVIDER_ID,
                    "MISSING_ARGUMENT",
                    "return_id is required",
                    null
            );
        }

        ReturnRecord record = returnRepository.findByReturnId(returnId.toUpperCase());
        if (record == null) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.RETURN_LOOKUP,
                    PROVIDER_ID,
                    "RETURN_NOT_FOUND",
                    "Return application was not found",
                    null
            );
        }

        Order order = orderRepository.findByOrderId(record.getOrderId());
        if (order == null) {
            // 退货记录指向不存在的订单属于数据完整性问题，不应伪装成“用户输错编号”。
            return CapabilityResult.technicalFailure(
                    CapabilityIds.RETURN_LOOKUP,
                    PROVIDER_ID,
                    "RETURN_ORDER_INCONSISTENT",
                    "Return application references a missing order"
            );
        }

        String principalUserId = context.getPrincipal().getUserId();
        if (!principalUserId.equals(order.getUserId()) || !tenantOf(context).equals(order.getTenantId())) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.RETURN_LOOKUP,
                    PROVIDER_ID,
                    "RETURN_OWNERSHIP_MISMATCH",
                    "The return application does not belong to the authenticated user",
                    null
            );
        }

        Product product = productRepository.findByProductId(order.getProductId());
        ReturnStatusSummary summary = new ReturnStatusSummary(
                record.getReturnId(),
                record.getOrderId(),
                product == null ? "Unknown Product" : product.getName(),
                record.getReason(),
                record.getStatus()
        );
        return CapabilityResult.success(
                CapabilityIds.RETURN_LOOKUP,
                PROVIDER_ID,
                "Return application found",
                summary
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
}
