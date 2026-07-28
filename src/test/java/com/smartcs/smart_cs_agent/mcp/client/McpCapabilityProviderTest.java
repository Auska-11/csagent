package com.smartcs.smart_cs_agent.mcp.client;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.HandoffTicketSummary;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderListResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnStatusSummary;
import com.smartcs.smart_cs_agent.capability.api.contracts.UserProfileSummary;
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
        transport.invokeMetadata = Map.of("diagnostics", List.of(Map.of(
                "eventName", "vector_retrieval_degraded",
                "stage", "search"
        )));
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
        assertTrue(result.getMetadata().containsKey("diagnostics"));
    }

    @Test
    void shouldRestoreOrderContractsFromRemoteJsonMaps() {
        FakeTransport singleTransport = new FakeTransport(List.of(readOnlyDefinition("order.lookup")));
        singleTransport.invokeData = remoteOrder("ORD-001");
        CapabilityResult<?> single = provider("mcp-remote", singleTransport).invoke(
                new CapabilityCall("order.lookup", Map.of("order_id", "ORD-001")),
                authenticatedContext()
        );

        FakeTransport listTransport = new FakeTransport(List.of(readOnlyDefinition("order.list-by-user")));
        listTransport.invokeData = Map.of(
                "user_id", "USER-001",
                "orders", List.of(remoteOrder("ORD-001"), remoteOrder("ORD-002"))
        );
        CapabilityResult<?> list = provider("mcp-remote", listTransport).invoke(
                new CapabilityCall("order.list-by-user", Map.of()),
                authenticatedContext()
        );

        assertTrue(single.getData() instanceof OrderSummary);
        assertEquals("ORD-001", ((OrderSummary) single.getData()).getOrderId());
        assertTrue(list.getData() instanceof OrderListResult);
        assertEquals(2, ((OrderListResult) list.getData()).getCount());
    }

    @Test
    void shouldRestoreReturnContractFromRemoteJsonMap() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("return.lookup")));
        transport.invokeData = Map.of(
                "return_id", "RET-001",
                "order_id", "ORD-004",
                "product_name", "Type-C 数据线",
                "reason", "数据线接口松动，充电不稳定",
                "status", "审核通过"
        );

        CapabilityResult<?> result = provider("mcp-remote", transport).invoke(
                new CapabilityCall("return.lookup", Map.of("return_id", "RET-001")),
                authenticatedContext()
        );

        ReturnStatusSummary summary = (ReturnStatusSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("RET-001", summary.getReturnId());
        assertEquals("ORD-004", summary.getOrderId());
        assertEquals("Type-C 数据线", summary.getProductName());
        assertEquals("审核通过", summary.getStatus());
    }

    @Test
    void shouldRestoreProductContractsFromRemoteJsonMaps() {
        FakeTransport searchTransport = new FakeTransport(List.of(readOnlyDefinition("product.search")));
        searchTransport.invokeData = Map.of(
                "keyword", "耳机",
                "count", 1,
                "products", List.of(remoteProduct())
        );
        CapabilityResult<?> search = provider("mcp-remote", searchTransport).invoke(
                new CapabilityCall("product.search", Map.of("keyword", "耳机")),
                authenticatedContext()
        );

        FakeTransport lookupTransport = new FakeTransport(List.of(readOnlyDefinition("product.lookup")));
        lookupTransport.invokeData = remoteProduct();
        CapabilityResult<?> lookup = provider("mcp-remote", lookupTransport).invoke(
                new CapabilityCall("product.lookup", Map.of("product_name", "耳机")),
                authenticatedContext()
        );

        ProductSearchResult products = (ProductSearchResult) search.getData();
        ProductSummary product = (ProductSummary) lookup.getData();
        assertEquals(1, products.getCount());
        assertEquals("蓝牙耳机 Pro Max", products.getProducts().get(0).getName());
        assertEquals("PROD-001", product.getProductId());
        assertEquals(599, product.getPrice());
    }

    @Test
    void shouldRestoreAndMaskUserProfileContractFromRemoteJsonMap() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("user.lookup")));
        transport.invokeData = Map.of(
                "user_id", "USER-001",
                "name", "张三",
                "phone", "13812341234"
        );

        CapabilityResult<?> result = provider("mcp-remote", transport).invoke(
                new CapabilityCall("user.lookup", Map.of()),
                authenticatedContext()
        );

        UserProfileSummary profile = (UserProfileSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("USER-001", profile.getUserId());
        assertEquals("张三", profile.getName());
        assertEquals("138****1234", profile.getPhoneMasked());
    }

    @Test
    void shouldRestoreHandoffTicketContractFromRemoteJsonMap() {
        FakeTransport transport = new FakeTransport(List.of(readOnlyDefinition("handoff.request")));
        transport.invokeData = Map.of(
                "ticket_id", "HD-REMOTE-001",
                "reason", "COMPLAINT",
                "status", "WAITING_HUMAN_AGENT",
                "created_at", "2026-07-12T12:00:00Z",
                "user_id", "USER-001",
                "conversation_id", "conv-remote"
        );

        CapabilityResult<?> result = provider("mcp-remote", transport).invoke(
                new CapabilityCall("handoff.request", Map.of(
                        "reason", "COMPLAINT",
                        "summary", "用户投诉"
                )),
                authenticatedContext()
        );

        HandoffTicketSummary ticket = (HandoffTicketSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("HD-REMOTE-001", ticket.getTicketId());
        assertEquals("COMPLAINT", ticket.getReason());
        assertEquals("WAITING_HUMAN_AGENT", ticket.getStatus());
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

    private Map<String, Object> remoteOrder(String orderId) {
        return Map.of(
                "order_id", orderId,
                "user_id", "USER-001",
                "product_id", "PROD-001",
                "product_name", "蓝牙耳机 Pro Max",
                "status", "已发货",
                "tracking_no", "SF1234567890"
        );
    }

    private Map<String, Object> remoteProduct() {
        return Map.of(
                "product_id", "PROD-001",
                "name", "蓝牙耳机 Pro Max",
                "price", 599,
                "stock", 100,
                "description", "支持主动降噪，续航30小时，蓝牙5.3"
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
        private Map<String, Object> invokeMetadata = Map.of();

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
                    invokeData == null ? call.getArguments() : invokeData,
                    invokeMetadata
            );
        }

        @Override
        public McpTransportHealth health() {
            return new McpTransportHealth(true, 0, 0, "");
        }
    }
}
