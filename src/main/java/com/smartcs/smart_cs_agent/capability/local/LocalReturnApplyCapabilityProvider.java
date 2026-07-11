package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.domain.returnservice.ReturnApplicationResult;
import com.smartcs.smart_cs_agent.domain.returnservice.ReturnApplicationService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * return.apply 的独立本地 Provider。
 *
 * <p>它不再委托旧 ToolRegistry，因此能够传递服务端 ExecutionContext，并把业务拒绝与技术失败
 * 准确区分。这是后续逐个迁移其他能力的参考实现。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalReturnApplyCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-return-domain";

    private final ReturnApplicationService returnApplicationService;
    private final CapabilityDefinition definition;

    public LocalReturnApplyCapabilityProvider(ReturnApplicationService returnApplicationService) {
        this.returnApplicationService = returnApplicationService;
        this.definition = buildDefinition();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        return List.of(definition);
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String orderId = stringArgument(call, "order_id");
        String reason = stringArgument(call, "reason");
        if (orderId == null || reason == null) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.RETURN_APPLY,
                    PROVIDER_ID,
                    "MISSING_ARGUMENT",
                    "order_id and reason are required",
                    null
            );
        }

        ReturnApplicationResult result = returnApplicationService.apply(orderId, reason, context);
        if (result.isSubmitted()) {
            return CapabilityResult.success(
                    CapabilityIds.RETURN_APPLY,
                    PROVIDER_ID,
                    result.getMessage(),
                    result.getData()
            );
        }
        return CapabilityResult.businessRejected(
                CapabilityIds.RETURN_APPLY,
                PROVIDER_ID,
                result.getCode(),
                result.getMessage(),
                result.getData()
        );
    }

    private String stringArgument(CapabilityCall call, String name) {
        Object value = call.getArguments().get(name);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private CapabilityDefinition buildDefinition() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "required", List.of("order_id", "reason"),
                "properties", Map.of(
                        "order_id", Map.of("type", "string", "description", "订单号"),
                        "reason", Map.of("type", "string", "description", "退货原因")
                )
        );
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "submitted", Map.of("type", "boolean"),
                        "reason_code", Map.of("type", "string"),
                        "return_id", Map.of("type", "string"),
                        "order_id", Map.of("type", "string"),
                        "status", Map.of("type", "string")
                )
        );
        return new CapabilityDefinition(
                CapabilityIds.RETURN_APPLY,
                "2.0.0",
                "为当前认证用户提交退货申请",
                inputSchema,
                outputSchema,
                true,
                true,
                true,
                true,
                30_000,
                CapabilityRiskLevel.HIGH
        );
    }
}
