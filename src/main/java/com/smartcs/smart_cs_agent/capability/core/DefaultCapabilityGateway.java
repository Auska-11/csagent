package com.smartcs.smart_cs_agent.capability.core;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCatalog;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 默认 CapabilityGateway：根据 Capability ID 将调用分发给注册的 Provider。
 *
 * <p>职责：建立目录、检查重复注册、选择 Provider、把未捕获异常转成统一技术失败。</p>
 *
 * <p>不负责：不理解退货或订单业务。Skill allowlist 由 CapabilityActionHandler 检查；本类只做
 * 所有 Provider 共用的 deadline、身份、认证和幂等 Guardrail。</p>
 */
@Component
public class DefaultCapabilityGateway implements CapabilityGateway {

    private static final String GATEWAY_PROVIDER_ID = "capability-gateway";

    private final Map<String, CapabilityProvider> providerByCapability;
    private final CapabilityCatalog catalog;
    private final CapabilityIdempotencyStore idempotencyStore;

    public DefaultCapabilityGateway(List<CapabilityProvider> providers) {
        this(providers, new CapabilityIdempotencyStore());
    }

    @Autowired
    public DefaultCapabilityGateway(List<CapabilityProvider> providers,
                                    CapabilityIdempotencyStore idempotencyStore) {
        List<CapabilityProvider> safeProviders = providers == null ? List.of() : List.copyOf(providers);
        Map<String, CapabilityProvider> providerMap = new LinkedHashMap<>();
        Collection<CapabilityDefinition> allDefinitions = new ArrayList<>();

        for (CapabilityProvider provider : safeProviders) {
            Objects.requireNonNull(provider, "Capability provider cannot be null");
            for (CapabilityDefinition definition : provider.definitions()) {
                CapabilityProvider previous = providerMap.putIfAbsent(definition.getId(), provider);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Capability " + definition.getId() + " is registered by both "
                                    + previous.providerId() + " and " + provider.providerId()
                    );
                }
                allDefinitions.add(definition);
            }
        }

        this.providerByCapability = Map.copyOf(providerMap);
        this.catalog = new CapabilityCatalog(allDefinitions);
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore cannot be null");
    }

    @Override
    public CapabilityCatalog catalog() {
        return catalog;
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        Objects.requireNonNull(call, "Capability call cannot be null");
        Objects.requireNonNull(context, "Execution context cannot be null");

        String canonicalId = CapabilityIds.canonicalize(call.getCapabilityId());
        CapabilityCall canonicalCall = canonicalId.equals(call.getCapabilityId())
                ? call
                : new CapabilityCall(canonicalId, call.getArguments());
        CapabilityProvider provider = providerByCapability.get(canonicalId);
        if (provider == null) {
            return CapabilityResult.technicalFailure(
                    canonicalId,
                    GATEWAY_PROVIDER_ID,
                    "CAPABILITY_NOT_FOUND",
                    "Unknown capability: " + canonicalId
            );
        }

        CapabilityDefinition definition = catalog.get(canonicalId);

        if (context.isExpired()) {
            return CapabilityResult.technicalFailure(
                    canonicalId,
                    provider.providerId(),
                    "CAPABILITY_DEADLINE_EXCEEDED",
                    "Capability call deadline has already expired"
            );
        }

        CapabilityResult<?> denied = validateExecutionContext(definition, context, provider.providerId());
        if (denied != null) {
            return denied;
        }

        if (definition.isIdempotent()) {
            String storageKey = String.join(
                    ":",
                    context.getTenantId(),
                    canonicalId,
                    context.getIdempotencyKey()
            );
            try {
                return idempotencyStore.execute(
                        storageKey,
                        fingerprint(canonicalCall),
                        () -> invokeProvider(provider, canonicalCall, context)
                );
            } catch (IdempotencyKeyConflictException e) {
                return CapabilityResult.technicalFailure(
                        canonicalId,
                        provider.providerId(),
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "The idempotency key was already used for different arguments"
                );
            }
        }

        return invokeProvider(provider, canonicalCall, context);
    }

    private CapabilityResult<?> validateExecutionContext(CapabilityDefinition definition,
                                                          ExecutionContext context,
                                                          String providerId) {
        if (definition.isRequiresIdentity()
                && (context.getPrincipal().getUserId() == null
                || context.getPrincipal().getUserId().isBlank())) {
            return CapabilityResult.businessRejected(
                    definition.getId(), providerId, "IDENTITY_REQUIRED",
                    "This capability requires a user identity", null
            );
        }
        if (definition.isRequiresAuthentication() && !context.getPrincipal().isAuthenticated()) {
            return CapabilityResult.businessRejected(
                    definition.getId(), providerId, "AUTHENTICATION_REQUIRED",
                    "This capability requires an authenticated user", null
            );
        }
        if (definition.isIdempotent()
                && (context.getIdempotencyKey() == null || context.getIdempotencyKey().isBlank())) {
            return CapabilityResult.businessRejected(
                    definition.getId(), providerId, "IDEMPOTENCY_KEY_REQUIRED",
                    "This side-effect capability requires an idempotency key", null
            );
        }
        return null;
    }

    private CapabilityResult<?> invokeProvider(CapabilityProvider provider,
                                               CapabilityCall call,
                                               ExecutionContext context) {

        try {
            CapabilityResult<?> result = provider.invoke(call, context);
            if (result == null) {
                return CapabilityResult.technicalFailure(
                        call.getCapabilityId(),
                        provider.providerId(),
                        "EMPTY_PROVIDER_RESULT",
                        "Capability provider returned no result"
                );
            }
            if (!call.getCapabilityId().equals(result.getCapabilityId())) {
                return CapabilityResult.technicalFailure(
                        call.getCapabilityId(),
                        provider.providerId(),
                        "CAPABILITY_CONTRACT_MISMATCH",
                        "Provider returned a result for a different capability"
                );
            }
            return result;
        } catch (RuntimeException exception) {
            // Gateway 是最后一道技术异常边界。这里只返回异常类型，不把数据库地址或堆栈暴露给用户。
            return CapabilityResult.technicalFailure(
                    call.getCapabilityId(),
                    provider.providerId(),
                    "CAPABILITY_PROVIDER_ERROR",
                    "Capability provider failed: " + exception.getClass().getSimpleName()
            );
        }
    }

    /**
     * 第一版使用排序后的参数 Map 生成稳定指纹。
     * 当前能力参数只包含字符串等简单值；若以后出现深层 JSON，可替换为标准 canonical JSON 哈希。
     */
    private String fingerprint(CapabilityCall call) {
        return call.getCapabilityId() + ":" + new TreeMap<>(call.getArguments());
    }
}
