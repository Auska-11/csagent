package com.smartcs.smart_cs_agent.mcp.client;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.capability.core.CapabilityProviderProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP Provider 的契约测试。
 *
 * <p>这里故意使用内存 Fake Transport，而不启动 HTTP 服务。这样测试关注 Provider 的职责：
 * “发现、命名空间、调用映射、错误归一化”，同时默认测试不依赖真实远程 MCP。</p>
 */
class McpCapabilityProviderTest {

    @Test
    void remoteModeShouldExposeCanonicalIdAndForwardOriginalContext() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("order.lookup")));
        McpCapabilityProvider provider = provider("mcp-remote", transport);
        ExecutionContext context = authenticatedContext();

        Collection<CapabilityDefinition> definitions = provider.definitions();
        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall("order.lookup", Map.of("order_id", "ORD-001")),
                context
        );

        assertEquals(List.of("order.lookup"), definitions.stream().map(CapabilityDefinition::getId).toList());
        assertEquals(CapabilityOutcome.SUCCESS, result.getOutcome());
        assertEquals("order.lookup", result.getCapabilityId());
        assertEquals("mcp:remote-cs", result.getProviderId());
        assertEquals("order.lookup", transport.lastCall.getCapabilityId());
        // 身份、会话、deadline 和幂等键必须原样穿过 Provider，不能重新从工具参数拼出来。
        assertSame(context, transport.lastContext);
    }

    @Test
    void hybridModeShouldNamespaceRemoteIdButInvokeOriginalRemoteCapability() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("knowledge.search")));
        McpCapabilityProvider provider = provider("hybrid", transport);
        String externalId = "mcp.remote-cs.knowledge.search";

        CapabilityDefinition exposed = provider.definitions().iterator().next();
        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(externalId, Map.of("query", "退货政策")),
                authenticatedContext()
        );

        assertEquals(externalId, exposed.getId());
        assertEquals("knowledge.search", transport.lastCall.getCapabilityId());
        assertEquals(externalId, result.getCapabilityId());
    }

    @Test
    void shouldCacheDiscoveryWithinConfiguredTtl() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("product.lookup")));
        McpCapabilityProvider provider = provider("mcp-remote", transport);

        provider.definitions();
        provider.definitions();

        assertEquals(1, transport.initializeCalls);
        assertEquals(1, transport.listCalls);
        assertTrue(provider.health().available());
    }

    @Test
    void shouldConvertTransportFailureToStableTechnicalFailure() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("order.lookup")));
        McpCapabilityProvider provider = provider("mcp-remote", transport);
        // 先完成发现，模拟“服务启动正常、运行中远端临时故障”。
        provider.definitions();
        transport.failInvoke = true;

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall("order.lookup", Map.of("order_id", "ORD-001")),
                authenticatedContext()
        );

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, result.getOutcome());
        assertEquals("MCP_REMOTE_UNAVAILABLE", result.getErrorCode());
        assertEquals("mcp:remote-cs", result.getProviderId());
    }

    @Test
    void shouldRestoreKnowledgeContractFromRemoteJsonMaps() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("knowledge.search")));
        transport.invokeData = List.of(Map.of(
                "documentId", "policy/warranty_policy.md",
                "category", "policy",
                "title", "保修政策",
                "score", 88,
                "snippet", "电子配件通常提供 1 年有限保修服务。"
        ));
        McpCapabilityProvider provider = provider("mcp-remote", transport);

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall("knowledge.search", Map.of("query", "保修多久")),
                authenticatedContext()
        );

        Object first = ((List<?>) result.getData()).get(0);
        assertTrue(first instanceof KnowledgeSearchHit);
        assertEquals("保修政策", ((KnowledgeSearchHit) first).getTitle());
    }

    @Test
    void hybridShouldKeepLocalApplicationStartableWhenRemoteDiscoveryFails() {
        FakeTransport transport = new FakeTransport(List.of());
        transport.failDiscovery = true;

        McpCapabilityProvider hybrid = provider("hybrid", transport);
        McpCapabilityProvider remoteOnly = provider("mcp-remote", transport);

        assertTrue(hybrid.definitions().isEmpty());
        assertThrows(McpTransportException.class, remoteOnly::definitions);
    }

    private McpCapabilityProvider provider(String mode, FakeTransport transport) {
        McpClientProperties client = new McpClientProperties();
        client.setServerId("remote-cs");
        client.setDiscoveryCacheTtlMs(60_000);

        CapabilityProviderProperties provider = new CapabilityProviderProperties();
        provider.setProviderMode(mode);
        return new McpCapabilityProvider(transport, client, provider);
    }

    private CapabilityDefinition readOnlyDefinition(String id) {
        return new CapabilityDefinition(
                id,
                "远程只读测试能力",
                Map.of("type", "object"),
                Map.of("type", "object")
        );
    }

    private ExecutionContext authenticatedContext() {
        return new ExecutionContext(
                "trace-mcp-001",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                "tenant-a",
                "conv-mcp-001",
                "idem-mcp-001",
                Instant.now().plusSeconds(10)
        );
    }

    /**
     * 最小 Fake 只实现网络端口，不了解 Provider 的命名空间规则。
     * 如果未来将 HTTP 换成 stdio，这组 Provider 测试仍然可以原样保留。
     */
    private static final class FakeTransport implements McpClientTransport {

        private final Collection<CapabilityDefinition> definitions;
        private int initializeCalls;
        private int listCalls;
        private CapabilityCall lastCall;
        private ExecutionContext lastContext;
        private boolean failInvoke;
        private boolean failDiscovery;
        private Object invokeData;

        private FakeTransport(Collection<CapabilityDefinition> definitions) {
            this.definitions = definitions;
        }

        @Override
        public McpRemoteServerInfo initialize() {
            initializeCalls++;
            if (failDiscovery) {
                throw new McpTransportException("simulated discovery failure");
            }
            return new McpRemoteServerInfo("2024-11-05", "fake-mcp", "1.0.0");
        }

        @Override
        public Collection<CapabilityDefinition> listCapabilities() {
            listCalls++;
            return definitions;
        }

        @Override
        public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
            if (failInvoke) {
                throw new McpTransportException("simulated network failure");
            }
            lastCall = call;
            lastContext = context;
            return CapabilityResult.success(
                    call.getCapabilityId(),
                    "fake-remote-provider",
                    "ok",
                    invokeData == null ? call.getArguments() : invokeData
            );
        }

        @Override
        public McpTransportHealth health() {
            return new McpTransportHealth(true, 0, 0, "");
        }
    }
}
