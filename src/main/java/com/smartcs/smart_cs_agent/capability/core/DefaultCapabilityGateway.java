package com.smartcs.smart_cs_agent.capability.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCatalog;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.api.CapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyExecutionInProgressException;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyKeyConflictException;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyWaitDeadlineExceededException;
import com.smartcs.smart_cs_agent.capability.idempotency.memory.InMemoryCapabilityIdempotencyStore;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private static final ObjectMapper FINGERPRINT_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Map<String, CapabilityProvider> providerByCapability;
    private final CapabilityCatalog catalog;
    private final CapabilityIdempotencyStore idempotencyStore;
    private final CapabilityExecutionGuard executionGuard;
    private final CapabilityTenantPolicy tenantPolicy;

    public DefaultCapabilityGateway(List<CapabilityProvider> providers) {
        // 手动 new Gateway 的单元测试默认使用内存实现；Spring 运行时会走下面的注入构造函数，
        // 根据 smartcs.persistence.mode 选择内存或 JDBC Store。
        this(
                providers,
                new InMemoryCapabilityIdempotencyStore(),
                CapabilityExecutionGuard.sharedDefault()
        );
    }

    public DefaultCapabilityGateway(List<CapabilityProvider> providers,
                                    CapabilityIdempotencyStore idempotencyStore) {
        this(providers, idempotencyStore, CapabilityExecutionGuard.sharedDefault());
    }

    /** 测试可以注入小容量执行器，稳定复现排队和饱和场景。 */
    public DefaultCapabilityGateway(List<CapabilityProvider> providers,
                                    CapabilityIdempotencyStore idempotencyStore,
                                    CapabilityInvocationExecutor invocationExecutor) {
        this(providers, idempotencyStore, new CapabilityExecutionGuard(invocationExecutor));
    }

    public DefaultCapabilityGateway(List<CapabilityProvider> providers,
                                    CapabilityIdempotencyStore idempotencyStore,
                                    CapabilityExecutionGuard executionGuard) {
        this(providers, idempotencyStore, executionGuard, CapabilityTenantPolicy.permissive());
    }

    /** Spring 生产构造函数：租户能力面策略在 Provider 执行前统一拦截（docs-76 §5.2）。 */
    @Autowired
    public DefaultCapabilityGateway(List<CapabilityProvider> providers,
                                    CapabilityIdempotencyStore idempotencyStore,
                                    CapabilityExecutionGuard executionGuard,
                                    CapabilityTenantPolicy tenantPolicy) {
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
        this.executionGuard = Objects.requireNonNull(
                executionGuard,
                "executionGuard cannot be null"
        );
        this.tenantPolicy = Objects.requireNonNull(tenantPolicy, "tenantPolicy cannot be null");
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

        // 租户能力面拦截先于 deadline、身份和幂等检查：被屏蔽的能力连幂等占位都不应占用，
        // 更不能启动 Provider。业务拒绝（而非技术失败）让 Workflow 能用正常话术收尾。
        if (tenantPolicy.isBlocked(context.getTenantId(), canonicalId)) {
            return CapabilityResult.businessRejected(
                    canonicalId,
                    provider.providerId(),
                    "CAPABILITY_BLOCKED_FOR_TENANT",
                    "当前渠道暂不支持在线办理该业务，如需处理请联系人工客服。",
                    null
            );
        }

        // 能力自己的默认超时不能突破上游 Turn deadline；反过来，上游给了更长预算时，
        // 每项能力仍只拿到契约声明的最大执行时间。
        ExecutionContext boundedContext = executionGuard.boundExecutionContext(definition, context);
        if (executionGuard.deadlineReached(boundedContext)) {
            return CapabilityResult.technicalFailure(
                    canonicalId,
                    provider.providerId(),
                    "CAPABILITY_DEADLINE_EXCEEDED",
                    "Capability call deadline has already expired"
            );
        }

        CapabilityResult<?> denied = validateExecutionContext(
                definition,
                boundedContext,
                provider.providerId()
        );
        if (denied != null) {
            return denied;
        }

        if (definition.isIdempotent()) {
            String storageKey = idempotencyStorageKey(context, canonicalId);
            try {
                return idempotencyStore.execute(
                        storageKey,
                        fingerprint(canonicalCall),
                        () -> {
                            CapabilityExecutionGuard.ProviderExecution execution = executionGuard.invoke(
                                    definition,
                                    provider,
                                    canonicalCall,
                                    boundedContext,
                                    () -> invokeProvider(provider, canonicalCall, boundedContext)
                            );
                            if (!execution.providerStarted()) {
                                /*
                                 * Provider 尚未开始，说明没有副作用不确定性。抛出内部异常让 Store 删除
                                 * IN_PROGRESS 占位；Gateway 随后把原结构化失败返回给调用方。
                                 */
                                throw new ProviderNotStartedException(execution.result());
                            }
                            return execution.result();
                        },
                        boundedContext.getDeadline()
                );
            } catch (ProviderNotStartedException e) {
                return e.result();
            } catch (IdempotencyKeyConflictException e) {
                return CapabilityResult.technicalFailure(
                        canonicalId,
                        provider.providerId(),
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "The idempotency key was already used for different arguments"
                );
            } catch (IdempotencyExecutionInProgressException e) {
                return CapabilityResult.technicalFailure(
                        canonicalId,
                        provider.providerId(),
                        "IDEMPOTENCY_EXECUTION_IN_PROGRESS",
                        "The same side-effect operation is still running"
                );
            } catch (IdempotencyWaitDeadlineExceededException e) {
                return CapabilityResult.technicalFailure(
                        canonicalId,
                        provider.providerId(),
                        "CAPABILITY_DEADLINE_EXCEEDED",
                        "Capability deadline expired while waiting for the existing operation"
                );
            } catch (RuntimeException e) {
                // 幂等数据库本身也是副作用安全边界。它不可用时不能绕过 Store 直接调用 Provider，
                // 否则一次基础设施故障就可能造成重复写入。
                return CapabilityResult.technicalFailure(
                        canonicalId,
                        provider.providerId(),
                        "IDEMPOTENCY_STORE_ERROR",
                        "The idempotency store is temporarily unavailable"
                );
            }
        }

        return executionGuard.invoke(
                definition,
                provider,
                canonicalCall,
                boundedContext,
                () -> invokeProvider(provider, canonicalCall, boundedContext)
        ).result();
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

    /** 使用 JSON Map 全层排序生成稳定指纹，嵌套参数的插入顺序不会影响幂等判断。 */
    private String fingerprint(CapabilityCall call) {
        try {
            return FINGERPRINT_MAPPER.writeValueAsString(Map.of(
                    "capabilityId", call.getCapabilityId(),
                    "arguments", call.getArguments()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Capability arguments cannot be fingerprinted", e);
        }
    }

    /**
     * 幂等回放必须隔离到可信的租户、用户和会话。
     *
     * <p>不能只依赖客户端给出的 idempotencyKey，否则同租户的另一个用户可以复用 key，在 Provider
     * 所有权校验之前拿到前一个用户的回放结果。长度前缀避免 ID 自带冒号时产生拼接歧义。</p>
     */
    private String idempotencyStorageKey(ExecutionContext context, String capabilityId) {
        return segment(context.getTenantId())
                + segment(context.getPrincipal().getUserId())
                + segment(context.getConversationId())
                + segment(capabilityId)
                + segment(context.getIdempotencyKey());
    }

    private String segment(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }

    /** 仅在 Provider 确认尚未开始时穿过 IdempotencyStore，触发安全释放占位。 */
    private static final class ProviderNotStartedException extends RuntimeException {
        private final CapabilityResult<?> result;

        private ProviderNotStartedException(CapabilityResult<?> result) {
            super("Capability provider did not start");
            this.result = result;
        }

        private CapabilityResult<?> result() {
            return result;
        }
    }
}
