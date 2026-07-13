package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.HandoffTicketSummary;
import com.smartcs.smart_cs_agent.tools.HandoffTicketData;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code handoff.request} 的独立本地 Provider。
 *
 * <p>最重要的安全边界是：userId 和 conversationId 不再由 Workflow/模型参数提供，而是只从
 * ExecutionContext 读取。即使调用参数里伪造了另一个用户或会话，本 Provider 也完全忽略。</p>
 *
 * <p>HandoffTools 暂时仍负责 memory/after-sales-http 两种具体工单实现；本类充当 Anti-Corruption
 * Layer，把旧工具 DTO 转成稳定 Capability Contract。未来替换工单系统时，上层 Workflow 不变。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalHandoffCapabilityProvider
        implements CapabilityProvider, LegacyCapabilityOverride {

    public static final String PROVIDER_ID = "local-handoff";

    private final HandoffTools handoffTools;
    private final CapabilityDefinition definition;

    public LocalHandoffCapabilityProvider(HandoffTools handoffTools) {
        this.handoffTools = handoffTools;
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
    public Set<String> overriddenCapabilityIds() {
        return Set.of(CapabilityIds.HANDOFF_REQUEST);
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String reason = stringArgument(call, "reason");
        String summary = stringArgument(call, "summary");
        if (reason == null || summary == null) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.HANDOFF_REQUEST,
                    PROVIDER_ID,
                    "MISSING_ARGUMENT",
                    "reason and summary are required",
                    null
            );
        }

        String userId = context.getPrincipal().getUserId();
        String conversationId = context.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.HANDOFF_REQUEST,
                    PROVIDER_ID,
                    "CONVERSATION_ID_REQUIRED",
                    "A server-side conversation id is required",
                    null
            );
        }

        HandoffTicketData ticket = handoffTools.createHandoffTicket(
                userId,
                conversationId,
                reason,
                summary,
                context.getIdempotencyKey()
        );
        HandoffTicketSummary contract = new HandoffTicketSummary(
                ticket.getTicketId(),
                ticket.getReason(),
                ticket.getStatus(),
                ticket.getCreatedAt()
        );
        return CapabilityResult.success(
                CapabilityIds.HANDOFF_REQUEST,
                PROVIDER_ID,
                "人工转接工单已创建",
                contract
        );
    }

    private String stringArgument(CapabilityCall call, String name) {
        Object value = call.getArguments().get(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private CapabilityDefinition buildDefinition() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "required", List.of("reason", "summary"),
                "properties", Map.of(
                        "reason", Map.of("type", "string", "description", "稳定的人工升级原因码"),
                        "summary", Map.of("type", "string", "description", "给人工客服看的问题摘要")
                )
        );
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "ticket_id", Map.of("type", "string"),
                        "reason", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "created_at", Map.of("type", "string")
                )
        );
        return new CapabilityDefinition(
                CapabilityIds.HANDOFF_REQUEST,
                "2.0.0",
                "使用服务端身份和会话上下文创建人工客服工单",
                inputSchema,
                outputSchema,
                true,
                false,
                true,
                true,
                30_000,
                CapabilityRiskLevel.MEDIUM
        );
    }
}
