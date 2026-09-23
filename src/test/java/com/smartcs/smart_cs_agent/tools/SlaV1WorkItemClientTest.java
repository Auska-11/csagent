package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.handoff.sla.InMemorySlaIntegrationStore;
import com.smartcs.smart_cs_agent.handoff.sla.SlaV1Properties;
import com.smartcs.smart_cs_agent.handoff.sla.SlaV1RequestIds;
import com.smartcs.smart_cs_agent.handoff.sla.SlaWorkItemAssociation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * SLA v1 建单客户端的契约行为：登录换 JWT、Idempotency-Key == requestId、超时对账、
 * 401 刷新重试一次、协议性拒绝不换键、关联先于远端调用落库。
 */
class SlaV1WorkItemClientTest {

    private static final String TENANT = "tenant-a";
    private static final String CONVERSATION = "xianyu_12345_67890";
    private static final String IDEMPOTENCY_KEY = "agent-handoff-fixed-key";

    private final InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();

    private SlaV1Properties properties() {
        SlaV1Properties properties = new SlaV1Properties();
        properties.setBaseUrl("http://sla-platform");
        properties.setUsername("agent-account");
        properties.setPassword("agent-password");
        properties.setSellerAccountId("seller-1");
        return properties;
    }

    private SlaV1WorkItemClient client(RestClient.Builder builder) {
        SlaV1Properties properties = properties();
        SlaV1AuthTokenService tokenService = new SlaV1AuthTokenService(properties, builder);
        return new SlaV1WorkItemClient(properties, store, tokenService, builder);
    }

    private AfterSalesTicketCreateRequest request() {
        return new AfterSalesTicketCreateRequest(
                "核实耳机蓝牙连接功能",
                "商品标注功能未测试，买家希望确认能否正常连接手机。",
                "USER-XY-001",
                "bluetooth",
                "support",
                "MEDIUM",
                IDEMPOTENCY_KEY
        );
    }

    private String requestId() {
        return SlaV1RequestIds.derive(TENANT, IDEMPOTENCY_KEY);
    }

    private static ResponseCreator loginSuccess(String token) {
        return withSuccess("""
                {
                  "success": true,
                  "data": {
                    "tokenType": "Bearer",
                    "accessToken": "%s",
                    "expiresAt": "2099-01-01T00:00:00Z"
                  },
                  "message": null
                }
                """.formatted(token), MediaType.APPLICATION_JSON);
    }

    private static String createSuccessBody(long workItemId, String requestId) {
        return """
                {
                  "success": true,
                  "data": {
                    "workItemId": %d,
                    "requestId": "%s",
                    "status": "PENDING",
                    "createdAt": "2026-09-08T02:00:00Z",
                    "resultVersion": 0
                  },
                  "message": null
                }
                """.formatted(workItemId, requestId);
    }

    /** 在返回建单成功响应之前确认关联已落库——证明 recordAssociation 先于远端副作用。 */
    private static ResponseCreator createSuccessAfterAssociationCheck(long workItemId,
                                                                      String requestId,
                                                                      InMemorySlaIntegrationStore store) {
        String body = createSuccessBody(workItemId, requestId);
        return (ClientHttpRequest request) -> {
            assertTrue(
                    store.findAssociationByRequestId(requestId).isPresent(),
                    "建单响应返回时，requestId 关联必须已经持久化"
            );
            return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
        };
    }

    @Test
    void shouldLoginCreateWorkItemPersistAssociationAndMapResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestId = requestId();
        SlaV1WorkItemClient client = client(builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(loginSuccess("token-1"));
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andExpect(header("Idempotency-Key", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.channel").value("XIANYU"))
                .andExpect(jsonPath("$.sellerAccountId").value("seller-1"))
                .andExpect(jsonPath("$.itemId").value("67890"))
                .andExpect(jsonPath("$.conversationId").value(CONVERSATION))
                .andExpect(jsonPath("$.buyerId").value("12345"))
                .andExpect(jsonPath("$.type").value("PRODUCT_CHECK"))
                .andExpect(jsonPath("$.title").value("核实耳机蓝牙连接功能"))
                .andExpect(jsonPath("$.description").exists())
                .andRespond(createSuccessAfterAssociationCheck(123, requestId, store));

        AfterSalesTicketCreateResult result = client.createTicket(TENANT, CONVERSATION, request());

        assertEquals("AS-123", result.ticketId());
        assertEquals("PENDING", result.status());
        assertEquals("2026-09-08T02:00:00Z", result.createdAt());
        SlaWorkItemAssociation stored = store.findAssociationByRequestId(requestId).orElseThrow();
        assertEquals("seller-1", stored.sellerAccountId());
        assertEquals("67890", stored.itemId());
        assertEquals("12345", stored.buyerId());
        assertEquals(Long.valueOf(123L), stored.workItemId());
        server.verify();
    }

    @Test
    void shouldReconcileByRequestIdAfterTimeoutWhenItemExists() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestId = requestId();
        SlaV1WorkItemClient client = client(builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-1"));
        // 建单读超时：远端可能已建单，必须先按 requestId 对账而不是盲目重试。
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andRespond(withException(new SocketTimeoutException("create timed out")));
        server.expect(requestTo(
                "http://sla-platform/api/integrations/v1/work-items/by-request/" + requestId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess(createSuccessBody(777, requestId), MediaType.APPLICATION_JSON));

        AfterSalesTicketCreateResult result = client.createTicket(TENANT, CONVERSATION, request());

        assertEquals("AS-777", result.ticketId());
        assertEquals(Long.valueOf(777L),
                store.findAssociationByRequestId(requestId).orElseThrow().workItemId());
        server.verify();
    }

    @Test
    void shouldSurfaceUnknownResultWhenReconciliationFindsNothing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestId = requestId();
        SlaV1WorkItemClient client = client(builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-1"));
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andRespond(withException(new SocketTimeoutException("create timed out")));
        server.expect(requestTo(
                "http://sla-platform/api/integrations/v1/work-items/by-request/" + requestId))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("{\"success\":false,\"data\":null,\"message\":\"NOT_FOUND\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.createTicket(TENANT, CONVERSATION, request())
        );

        assertEquals("SLA v1 work item not visible by requestId; awaiting reconciliation",
                error.getMessage());
        // 404 只说明尚无可见记录：关联仍在，恢复执行器稍后用原键原请求重试。
        assertTrue(store.findAssociationByRequestId(requestId).isPresent());
        assertEquals(null, store.findAssociationByRequestId(requestId).orElseThrow().workItemId());
        server.verify();
    }

    @Test
    void shouldRefreshTokenOnceAndRetryCreateWithSameIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestId = requestId();
        SlaV1WorkItemClient client = client(builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-expired"));
        // 第一次建单 401：缓存令牌已被服务端吊销。
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andExpect(header("Idempotency-Key", requestId))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"success\":false,\"data\":null,\"message\":\"UNAUTHORIZED\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-refreshed"));
        // 重试必须保持原请求原幂等键：只允许 Authorization 变化。
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andExpect(header("Authorization", "Bearer token-refreshed"))
                .andExpect(header("Idempotency-Key", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andRespond(withSuccess(
                        createSuccessBody(123, requestId), MediaType.APPLICATION_JSON));

        AfterSalesTicketCreateResult result = client.createTicket(TENANT, CONVERSATION, request());

        assertEquals("AS-123", result.ticketId());
        server.verify();
    }

    @Test
    void shouldFailAfterSecondUnauthorized() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestId = requestId();
        SlaV1WorkItemClient client = client(builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-1"));
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"success\":false,\"data\":null,\"message\":\"UNAUTHORIZED\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-2"));
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("{\"success\":false,\"data\":null,\"message\":\"UNAUTHORIZED\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        HandoffTicketCreationRejectedException error = assertThrows(
                HandoffTicketCreationRejectedException.class,
                () -> client.createTicket(TENANT, CONVERSATION, request())
        );

        assertEquals("SLA v1 authentication failed after token refresh retry", error.getMessage());
        server.verify();
    }

    @Test
    void shouldTranslateProtocolRejectionWithoutChangingKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String requestId = requestId();
        SlaV1WorkItemClient client = client(builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(loginSuccess("token-1"));
        // 同键异参：409 IDEMPOTENCY_CONFLICT 属于协议性失败，必须显式失败而不是换键硬重试。
        server.expect(requestTo("http://sla-platform/api/integrations/v1/work-items"))
                .andExpect(header("Idempotency-Key", requestId))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .body("{\"success\":false,\"data\":null,\"message\":\"IDEMPOTENCY_CONFLICT\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        HandoffTicketCreationRejectedException error = assertThrows(
                HandoffTicketCreationRejectedException.class,
                () -> client.createTicket(TENANT, CONVERSATION, request())
        );

        assertEquals("SLA v1 work item creation rejected: HTTP 409 IDEMPOTENCY_CONFLICT",
                error.getMessage());
        server.verify();
    }

    @Test
    void shouldFailClosedForNonXianyuConversations() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlaV1WorkItemClient client = client(builder);

        // 非闲鱼会话无法推导原买家/原商品坐标：绝不能猜测，也绝不能发出 HTTP 请求。
        // 坐标不可得是确定性失败，按拒绝分类直接转人工复核，不进入自动重试。
        HandoffTicketCreationRejectedException error = assertThrows(
                HandoffTicketCreationRejectedException.class,
                () -> client.createTicket(TENANT, "webchat-conv-1", request())
        );

        assertEquals("SLA v1 work items require a xianyu_{buyerId}_{itemId} conversation",
                error.getMessage());
        // 未声明任何期望：若客户端发出了 HTTP 请求，Mock 会在调用点直接失败，verify 也会失败。
        server.verify();
        assertTrue(store.findAssociationByRequestId(requestId()).isEmpty());
    }

    @Test
    void shouldRequireSellerAccountConfiguration() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlaV1Properties properties = properties();
        properties.setSellerAccountId("  ");
        SlaV1AuthTokenService tokenService = new SlaV1AuthTokenService(properties, builder);
        SlaV1WorkItemClient client = new SlaV1WorkItemClient(properties, store, tokenService, builder);

        HandoffTicketCreationRejectedException error = assertThrows(
                HandoffTicketCreationRejectedException.class,
                () -> client.createTicket(TENANT, CONVERSATION, request())
        );

        assertEquals(
                "smartcs.handoff.sla-v1.seller-account-id is required in sla-v1 mode",
                error.getMessage()
        );
        server.verify();
    }
}
