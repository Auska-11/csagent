package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.UserProfileSummary;
import com.smartcs.smart_cs_agent.model.User;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 当前认证用户资料的独立本地 Provider。
 *
 * <p>调用参数里即使出现 token 或 user_id 也会被忽略。真正查询键只能来自服务端已经认证的
 * ExecutionContext.principal，避免用户或模型伪造另一个账号。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalUserProfileCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-user-profile";

    private final UserRepository userRepository;
    private final CapabilityDefinition definition;

    public LocalUserProfileCapabilityProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
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
        String userId = context.getPrincipal().getUserId();
        User user = userRepository.findByUserId(userId);
        if (user == null) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.USER_LOOKUP,
                    PROVIDER_ID,
                    "USER_NOT_FOUND",
                    "Authenticated user profile was not found",
                    null
            );
        }

        UserProfileSummary profile = new UserProfileSummary(
                user.getUserId(),
                user.getName(),
                user.getPhone()
        );
        return CapabilityResult.success(
                CapabilityIds.USER_LOOKUP,
                PROVIDER_ID,
                "User profile found",
                profile
        );
    }

    private CapabilityDefinition buildDefinition() {
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string"),
                        "name", Map.of("type", "string"),
                        "phone_masked", Map.of("type", "string")
                )
        );
        return new CapabilityDefinition(
                CapabilityIds.USER_LOOKUP,
                "1.0.0",
                "查询当前认证用户自己的基础资料",
                Map.of("type", "object", "properties", Map.of()),
                outputSchema,
                true,
                true,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }
}
