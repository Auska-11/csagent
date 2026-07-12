package com.smartcs.smart_cs_agent.mcp.client;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

/**
 * HTTP MCP Client 的协议边界测试。
 *
 * <p>MockRestServiceServer 会接管 RestClient，不访问真实网络；测试仍会校验实际发送的 JSON-RPC
 * method、HTTP Bearer 认证，以及响应到 Capability 契约的映射。</p>
 */
class HttpMcpClientTransportTest {

    @Test
    void shouldInitializeDiscoverAndInvokeThroughJsonRpc() {
        Fixture fixture = fixture();

        fixture.server.expect(requestTo(fixture.properties.getBaseUrl()))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer service-secret"))
                .andExpect(jsonPath("$.method").value("initialize"))
                .andRespond(withSuccess("""
                        {
                          "jsonrpc":"2.0",
                          "id":"init",
                          "result":{
                            "protocolVersion":"2024-11-05",
                            "serverInfo":{"name":"remote-cs","version":"2.0.0"}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(requestTo(fixture.properties.getBaseUrl()))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer service-secret"))
                .andExpect(jsonPath("$.method").value("tools/list"))
                .andRespond(withSuccess("""
                        {
                          "jsonrpc":"2.0",
                          "id":"list",
                          "result":{"tools":[{
                            "name":"knowledge.search",
                            "description":"远程知识检索",
                            "inputSchema":{"type":"object"},
                            "outputSchema":{"type":"array"},
                            "_meta":{
                              "version":"1.0.0",
                              "requiresIdentity":false,
                              "requiresAuthentication":false,
                              "sideEffect":false,
                              "idempotent":false,
                              "defaultTimeoutMs":5000,
                              "riskLevel":"READ_ONLY"
                            }
                          }]}
                        }
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(requestTo(fixture.properties.getBaseUrl()))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer service-secret"))
                .andExpect(jsonPath("$.method").value("tools/call"))
                .andExpect(jsonPath("$.params.name").value("knowledge.search"))
                .andExpect(jsonPath("$.params._meta.authToken").doesNotExist())
                .andExpect(jsonPath("$.params._meta.userId").doesNotExist())
                .andExpect(jsonPath("$.params._meta.idempotencyKey").value("idem-http"))
                .andRespond(withSuccess("""
                        {
                          "jsonrpc":"2.0",
                          "id":"trace-http",
                          "result":{
                            "content":[{"type":"text","text":"命中保修政策"}],
                            "structuredContent":[{"title":"保修政策","snippet":"通常提供 1 年保修"}],
                            "isError":false,
                            "_meta":{"providerId":"remote-knowledge","replayed":true}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        McpRemoteServerInfo info = fixture.transport.initialize();
        Collection<CapabilityDefinition> definitions = fixture.transport.listCapabilities();
        CapabilityResult<?> result = fixture.transport.invoke(
                new CapabilityCall("knowledge.search", Map.of("query", "保修多久")),
                authenticatedContext()
        );

        assertEquals("remote-cs", info.name());
        assertEquals("knowledge.search", definitions.iterator().next().getId());
        assertTrue(result.isSuccess());
        assertTrue(result.isReplayed());
        assertEquals("remote-knowledge", result.getProviderId());
        fixture.server.verify();
    }

    @Test
    void shouldRefuseToElevateUnverifiedPrincipalWithServiceToken() {
        Fixture fixture = fixture();
        ExecutionContext unverified = new ExecutionContext(
                "trace-unverified",
                AuthenticatedPrincipal.unverifiedClaim("USER-001"),
                "default",
                "conv-unverified",
                "",
                Instant.now().plusSeconds(5)
        );

        assertThrows(
                McpTransportException.class,
                () -> fixture.transport.invoke(
                        new CapabilityCall("knowledge.search", Map.of("query", "保修")),
                        unverified
                )
        );
        fixture.server.verify();
    }

    @Test
    void shouldRetryOnlyConfiguredTimesThenOpenCircuit() {
        Fixture fixture = fixture();
        fixture.properties.setMaxRetries(1);
        fixture.properties.setCircuitFailureThreshold(1);
        fixture.properties.setCircuitOpenMs(60_000);

        fixture.server.expect(ExpectedCount.twice(), requestTo(fixture.properties.getBaseUrl()))
                .andRespond(withServerError());

        assertThrows(McpTransportException.class, fixture.transport::initialize);
        assertFalse(fixture.transport.health().available());

        // 熔断打开后立即失败，不应产生第三次 HTTP 请求。
        assertThrows(McpTransportException.class, fixture.transport::initialize);
        fixture.server.verify();
    }

    private Fixture fixture() {
        McpClientProperties properties = new McpClientProperties();
        properties.setBaseUrl("http://remote.test/mcp");
        properties.setServerId("remote-cs");
        properties.setServiceToken("service-secret");
        properties.setMaxRetries(0);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpMcpClientTransport transport = new HttpMcpClientTransport(properties, builder.build());
        return new Fixture(properties, server, transport);
    }

    private ExecutionContext authenticatedContext() {
        return new ExecutionContext(
                "trace-http",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                "tenant-a",
                "conv-http",
                "idem-http",
                Instant.now().plusSeconds(10)
        );
    }

    private record Fixture(McpClientProperties properties,
                           MockRestServiceServer server,
                           HttpMcpClientTransport transport) {
    }
}
