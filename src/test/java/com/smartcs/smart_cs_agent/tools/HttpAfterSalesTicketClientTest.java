package com.smartcs.smart_cs_agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAfterSalesTicketClientTest {

    @Test
    void shouldCallAfterSalesTicketApiWithRequiredHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HandoffProperties properties = createProperties();
        HttpAfterSalesTicketClient client = new HttpAfterSalesTicketClient(properties, builder);

        server.expect(requestTo("http://ticket-platform/api/tickets"))
                .andExpect(method(HttpMethod.POST))
                // 请求头必须来自本次可信执行上下文，而不是一份所有请求共用的静态配置。
                .andExpect(header("X-Tenant-Id", "tenant-from-context"))
                .andExpect(header("X-User-Id", "agent-service"))
                .andExpect(header("X-User-Role", "AGENT"))
                .andExpect(header("Idempotency-Key", "agent:conv-001:handoff:COMPLAINT"))
                .andExpect(jsonPath("$.customerId").value("USER-001"))
                .andExpect(jsonPath("$.productLine").value("bluetooth"))
                .andExpect(jsonPath("$.issueType").value("complaint"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "id": 123,
                            "status": "PENDING",
                            "createdAt": "2026-07-09T00:00:00Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AfterSalesTicketCreateResult result = client.createTicket(
                "tenant-from-context",
                new AfterSalesTicketCreateRequest(
                        "Agent 转人工：用户投诉蓝牙耳机坏了",
                        "用户ID：USER-001",
                        "USER-001",
                        "bluetooth",
                        "complaint",
                        "URGENT",
                        "agent:conv-001:handoff:COMPLAINT"
                )
        );

        assertEquals("AS-123", result.ticketId());
        assertEquals("PENDING", result.status());
        assertEquals("2026-07-09T00:00:00Z", result.createdAt());
        server.verify();
    }

    @Test
    void shouldThrowWhenAfterSalesApiReturnsFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HandoffProperties properties = createProperties();
        HttpAfterSalesTicketClient client = new HttpAfterSalesTicketClient(properties, builder);

        server.expect(requestTo("http://ticket-platform/api/tickets"))
                .andRespond(withSuccess("""
                        {
                          "success": false,
                          "message": "bad request"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> client.createTicket(
                "tenant-from-context",
                new AfterSalesTicketCreateRequest(
                        "Agent 转人工：用户投诉",
                        "用户ID：USER-001",
                        "USER-001",
                        "general",
                        "complaint",
                        "URGENT",
                        "agent:conv-001:handoff:COMPLAINT"
                )
        ));
        server.verify();
    }

    @Test
    void shouldRejectIncompleteSuccessInsteadOfFabricatingTicketEvidence() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpAfterSalesTicketClient client = new HttpAfterSalesTicketClient(
                createProperties(),
                builder
        );
        server.expect(requestTo("http://ticket-platform/api/tickets"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "id": 123,
                            "status": "PENDING"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> client.createTicket(
                        "tenant-from-context",
                        new AfterSalesTicketCreateRequest(
                                "Agent 转人工：用户投诉",
                                "用户ID：USER-001",
                                "USER-001",
                                "general",
                                "complaint",
                                "URGENT",
                                "agent:conv-001:handoff:INCOMPLETE"
                        )
                )
        );

        assertEquals("售后工单平台返回的工单结果不完整", exception.getMessage());
        server.verify();
    }

    private HandoffProperties createProperties() {
        HandoffProperties properties = new HandoffProperties();
        properties.getAfterSales().setBaseUrl("http://ticket-platform");
        properties.getAfterSales().setAgentUserId("agent-service");
        properties.getAfterSales().setAgentUserRole("AGENT");
        return properties;
    }
}
