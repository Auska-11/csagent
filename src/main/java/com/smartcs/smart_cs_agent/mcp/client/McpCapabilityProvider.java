package com.smartcs.smart_cs_agent.mcp.client;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.capability.core.CapabilityProviderProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将远程 MCP Server 暴露为普通 CapabilityProvider。
 *
 * <p>mcp-remote 模式保留远程 canonical ID，因此 Skill/Workflow 无需修改；hybrid 模式增加
 * {@code mcp.serverId.} 前缀，避免与本地能力冲突。工具发现使用带 TTL 的不可变缓存。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'local'")
public class McpCapabilityProvider implements CapabilityProvider {

    private final McpClientTransport transport;
    private final McpClientProperties clientProperties;
    private final CapabilityProviderProperties providerProperties;

    private volatile List<CapabilityDefinition> cachedDefinitions = List.of();
    private volatile Map<String, String> externalToRemoteId = Map.of();
    private volatile long cacheExpiresAt;
    private volatile McpRemoteServerInfo serverInfo;

    public McpCapabilityProvider(McpClientTransport transport,
                                 McpClientProperties clientProperties,
                                 CapabilityProviderProperties providerProperties) {
        this.transport = transport;
        this.clientProperties = clientProperties;
        this.providerProperties = providerProperties;
    }

    @Override
    public String providerId() {
        return "mcp:" + clientProperties.getServerId();
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        long now = System.currentTimeMillis();
        if (!cachedDefinitions.isEmpty() && now < cacheExpiresAt) {
            return cachedDefinitions;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (!cachedDefinitions.isEmpty() && now < cacheExpiresAt) {
                return cachedDefinitions;
            }
            try {
                refreshDiscovery(now);
            } catch (McpTransportException e) {
                // hybrid 的首要承诺是“远端坏了，本地能力仍可启动”。
                // remote-only 没有可替代 Provider，继续 fail-fast 能更早暴露部署配置错误。
                if (!providerProperties.isHybrid()) {
                    throw e;
                }
            }
            return cachedDefinitions;
        }
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        // 确保映射已初始化；Gateway 启动时通常已经调用过 definitions。
        definitions();
        String remoteId = externalToRemoteId.get(call.getCapabilityId());
        if (remoteId == null) {
            return CapabilityResult.technicalFailure(
                    call.getCapabilityId(), providerId(), "MCP_CAPABILITY_NOT_DISCOVERED",
                    "Capability was not discovered on remote MCP server"
            );
        }

        try {
            CapabilityResult<?> remote = transport.invoke(
                    new CapabilityCall(remoteId, call.getArguments()),
                    context
            );
            return remapResult(call.getCapabilityId(), remote);
        } catch (McpTransportException e) {
            return CapabilityResult.technicalFailure(
                    call.getCapabilityId(),
                    providerId(),
                    "MCP_REMOTE_UNAVAILABLE",
                    "Remote MCP server is unavailable"
            );
        }
    }

    public McpTransportHealth health() {
        return transport.health();
    }

    public McpRemoteServerInfo getServerInfo() {
        definitions();
        return serverInfo;
    }

    private void refreshDiscovery(long now) {
        serverInfo = transport.initialize();
        Collection<CapabilityDefinition> remoteDefinitions = transport.listCapabilities();
        List<CapabilityDefinition> exposed = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();

        for (CapabilityDefinition remote : remoteDefinitions) {
            String externalId = providerProperties.isHybrid()
                    ? "mcp." + clientProperties.getServerId() + "." + remote.getId()
                    : remote.getId();
            if (mapping.putIfAbsent(externalId, remote.getId()) != null) {
                throw new McpTransportException("Duplicate remote MCP capability: " + externalId);
            }
            exposed.add(copyWithId(remote, externalId));
        }

        cachedDefinitions = List.copyOf(exposed);
        externalToRemoteId = Map.copyOf(mapping);
        cacheExpiresAt = now + Math.max(1, clientProperties.getDiscoveryCacheTtlMs());
    }

    private CapabilityDefinition copyWithId(CapabilityDefinition source, String id) {
        return new CapabilityDefinition(
                id,
                source.getVersion(),
                source.getDescription(),
                source.getInputSchema(),
                source.getOutputSchema(),
                source.isRequiresIdentity(),
                source.isRequiresAuthentication(),
                source.isSideEffect(),
                source.isIdempotent(),
                source.getDefaultTimeoutMs(),
                source.getRiskLevel()
        );
    }

    private CapabilityResult<?> remapResult(String externalId, CapabilityResult<?> remote) {
        Object normalizedData = normalizeContractData(remote.getCapabilityId(), remote.getData());
        CapabilityResult<?> result;
        if (remote.getOutcome() == CapabilityOutcome.SUCCESS) {
            result = CapabilityResult.success(externalId, providerId(), remote.getMessage(), normalizedData);
        } else if (remote.getOutcome() == CapabilityOutcome.BUSINESS_REJECTED) {
            result = CapabilityResult.businessRejected(
                    externalId, providerId(), remote.getErrorCode(), remote.getMessage(), normalizedData
            );
        } else {
            result = CapabilityResult.technicalFailure(
                    externalId, providerId(), remote.getErrorCode(), remote.getMessage()
            );
        }
        return remote.isReplayed() ? result.asReplayed() : result;
    }

    /**
     * HTTP JSON 不会保留 Java DTO 类型，因此在 MCP Provider 边界恢复稳定输出契约。
     * 这个转换放在 Provider，而不是 Warranty Workflow 中，保证业务流程与传输协议无关。
     */
    private Object normalizeContractData(String remoteCapabilityId, Object data) {
        if (!CapabilityIds.KNOWLEDGE_SEARCH.equals(CapabilityIds.canonicalize(remoteCapabilityId))
                || !(data instanceof List<?> rows)) {
            return data;
        }
        return rows.stream()
                .map(row -> row instanceof KnowledgeSearchHit hit ? hit : KnowledgeSearchHit.fromMap(row))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
