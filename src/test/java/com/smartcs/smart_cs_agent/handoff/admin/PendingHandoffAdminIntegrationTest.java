package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffClaim;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStore;
import com.smartcs.smart_cs_agent.security.DemoJwtTokenService;
import com.smartcs.smart_cs_agent.security.SecurityProperties;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 20.4 的真实 HTTP 闭环测试。
 *
 * <p>Controller MVC 测试里的 Service 是 Mock，只能证明 URL 和 JSON 映射正确。本测试改用真实 JWT、
 * SecurityFilterChain、Admin Service 和 Memory Store，验证一次人工决定确实同时改变主记录并留下审计。</p>
 */
@SpringBootTest(properties = {
        "smartcs.test.context=pending-handoff-admin-integration",
        "smartcs.security.demo-issuer-enabled=true",
        "smartcs.security.jwt-secret=test-only-smart-cs-jwt-secret-32-bytes-ok"
})
@AutoConfigureMockMvc
class PendingHandoffAdminIntegrationTest {

    private static final String ADMIN_USER_ID = "USER-001";
    private static final String PRIVATE_USER_ID = "private-user@example.com";
    private static final String PRIVATE_CONVERSATION_ID = "private-conversation";
    private static final String PRIVATE_CUSTOMER_ID = "PRIVATE-CUSTOMER";
    private static final String PRIVATE_PHONE = "13800138000";
    private static final String PRIVATE_TOKEN = "super-secret-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PendingHandoffStore pendingHandoffStore;

    @Autowired
    private DemoJwtTokenService tokenService;

    @Autowired
    private SecurityProperties securityProperties;

    @Test
    void shouldApplyAllAdminDecisionsWithJwtIdentityAndQueryableAudit() throws Exception {
        String testRun = UUID.randomUUID().toString();
        PendingHandoffRecord retrySource = moveToManualReview("retry-" + testRun);
        PendingHandoffRecord confirmSource = moveToManualReview("confirm-" + testRun);
        PendingHandoffRecord closeSource = moveToManualReview("close-" + testRun);
        PendingHandoffRecord reconcileSource = moveToManualReview("reconcile-" + testRun);
        RequestPostProcessor adminBearer = bearer(adminToken());

        // 先通过真实列表和详情读取 Store 中的数据。列表刻意没有自由文本，详情只保留脱敏预览。
        MvcResult list = mockMvc.perform(get("/api/admin/pending-handoffs")
                        .param("status", PendingHandoffStatus.NEEDS_MANUAL_REVIEW.name())
                        .with(adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].summaryPreview").doesNotExist())
                .andReturn();
        assertNoRecordSecrets(list);

        MvcResult detail = mockMvc.perform(get(
                        "/api/admin/pending-handoffs/{outboxId}", confirmSource.outboxId())
                        .with(bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MANUAL_REVIEW"))
                .andExpect(jsonPath("$.handoffReasonCode").value("COMPLAINT"))
                .andExpect(jsonPath("$.summaryPreview").value(org.hamcrest.Matchers.containsString("[REDACTED]")))
                .andReturn();
        assertNoRecordSecrets(detail);

        // 当前独立售后平台没有只读幂等查询路由。默认 Adapter 必须明确返回 503，而不是把未知伪装成
        // 远端不存在；失败后主记录版本、状态和审计历史都保持不变。
        MvcResult unavailable = mockMvc.perform(post(
                        "/api/admin/pending-handoffs/{outboxId}/reconcile",
                        reconcileSource.outboxId())
                        .with(bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(
                                reconcileSource.version(),
                                "SERVER_LOOKUP_REQUESTED"
                        )))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode")
                        .value("PENDING_HANDOFF_RESULT_QUERY_UNAVAILABLE"))
                .andReturn();
        assertNoRecordSecrets(unavailable);
        PendingHandoffRecord unchanged = pendingHandoffStore.findByKey(
                reconcileSource.key()
        ).orElseThrow();
        assertEquals(PendingHandoffStatus.NEEDS_MANUAL_REVIEW, unchanged.status());
        assertEquals(reconcileSource.version(), unchanged.version());
        mockMvc.perform(get(
                        "/api/admin/pending-handoffs/{outboxId}/audit-events",
                        reconcileSource.outboxId())
                        .with(bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));

        // 请求体中的 tenantId、actorId 和 evidenceSource 不是 DTO 字段。即使客户端伪造这些值，
        // Service 构造领域命令时也只能使用 JWT Principal 中已经验证过的租户和管理员身份。
        MvcResult retry = mockMvc.perform(post(
                        "/api/admin/pending-handoffs/{outboxId}/retry", retrySource.outboxId())
                        .with(bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(retrySource.version(), "MANUAL_RETRY_APPROVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.status").value("RETRY_SCHEDULED"))
                .andExpect(jsonPath("$.record.version").value(retrySource.version() + 1))
                .andExpect(jsonPath("$.auditEvent.action").value("RETRY"))
                .andExpect(jsonPath("$.auditEvent.actorId").value(ADMIN_USER_ID))
                .andExpect(jsonPath("$.auditEvent.authorityUsed").value("ADMIN"))
                .andExpect(jsonPath("$.auditEvent.evidenceSource").value("NONE"))
                .andReturn();
        assertNoRecordSecrets(retry);
        assertForgedIdentityWasIgnored(retry);

        MvcResult confirm = mockMvc.perform(post(
                        "/api/admin/pending-handoffs/{outboxId}/confirm", confirmSource.outboxId())
                        .with(bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(confirmSource.version())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.record.confirmedTicket.ticketId").value("AS-20-4"))
                .andExpect(jsonPath("$.record.confirmedTicket.createdAt").value("2026-07-13T00:00:00Z"))
                .andExpect(jsonPath("$.auditEvent.action").value("CONFIRM"))
                .andExpect(jsonPath("$.auditEvent.actorId").value(ADMIN_USER_ID))
                .andExpect(jsonPath("$.auditEvent.evidenceSource").value("ADMIN_PROVIDED"))
                .andReturn();
        assertNoRecordSecrets(confirm);
        assertForgedIdentityWasIgnored(confirm);

        MvcResult close = mockMvc.perform(post(
                        "/api/admin/pending-handoffs/{outboxId}/close", closeSource.outboxId())
                        .with(bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(closeSource.version(), "NO_TICKET_REQUIRED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.status").value("MANUALLY_CLOSED"))
                .andExpect(jsonPath("$.record.scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.auditEvent.action").value("CLOSE"))
                .andExpect(jsonPath("$.auditEvent.actorId").value(ADMIN_USER_ID))
                .andExpect(jsonPath("$.auditEvent.evidenceSource").value("NONE"))
                .andReturn();
        assertNoRecordSecrets(close);
        assertForgedIdentityWasIgnored(close);

        // 决定接口返回的 auditEvent 不是临时拼出的展示数据；重新查询三个时间线仍能读到同一业务事实。
        assertAuditTimeline(retrySource, "RETRY", "RETRY_SCHEDULED", "NONE");
        assertAuditTimeline(confirmSource, "CONFIRM", "CONFIRMED", "ADMIN_PROVIDED");
        assertAuditTimeline(closeSource, "CLOSE", "MANUALLY_CLOSED", "NONE");
    }

    private PendingHandoffRecord moveToManualReview(String idempotencyKey) {
        PendingHandoffRecord prepared = pendingHandoffStore.prepare(command(idempotencyKey)).record();

        /*
         * 正常生产中由 Recovery Worker 领取到期记录并判断自动恢复失败。测试直接调用同一个 Store Port
         * 推进到人工复核，这样无需等待 30 秒宽限，也没有绕过真实状态机。
         */
        PendingHandoffClaim claim = pendingHandoffStore.tryClaim(
                prepared.key(),
                prepared.version(),
                "admin-integration-setup-" + idempotencyKey,
                prepared.nextAttemptAt(),
                1_000
        ).orElseThrow();
        return pendingHandoffStore.markNeedsManualReview(claim, "SocketTimeoutException");
    }

    private PendingHandoffCommand command(String idempotencyKey) {
        return new PendingHandoffCommand(
                securityProperties.getDefaultTenantId(),
                PRIVATE_USER_ID,
                PRIVATE_CONVERSATION_ID,
                "COMPLAINT",
                PRIVATE_USER_ID + " " + PRIVATE_PHONE + " token=" + PRIVATE_TOKEN,
                new AfterSalesTicketCreateRequest(
                        "private request title",
                        "private request description",
                        PRIVATE_CUSTOMER_ID,
                        "general",
                        "complaint",
                        "HIGH",
                        idempotencyKey
                )
        );
    }

    private void assertAuditTimeline(PendingHandoffRecord source,
                                     String action,
                                     String targetStatus,
                                     String evidenceSource) throws Exception {
        MvcResult result = mockMvc.perform(get(
                        "/api/admin/pending-handoffs/{outboxId}/audit-events", source.outboxId())
                        .with(bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].action").value(action))
                .andExpect(jsonPath("$.events[0].actorId").value(ADMIN_USER_ID))
                .andExpect(jsonPath("$.events[0].fromStatus").value("NEEDS_MANUAL_REVIEW"))
                .andExpect(jsonPath("$.events[0].toStatus").value(targetStatus))
                .andExpect(jsonPath("$.events[0].evidenceSource").value(evidenceSource))
                .andExpect(jsonPath("$.events[0].outboxId").doesNotExist())
                .andExpect(jsonPath("$.events[0].tenantId").doesNotExist())
                .andReturn();
        assertNoRecordSecrets(result);
    }

    private String decisionBody(long expectedVersion, String reasonCode) {
        return """
                {
                  "expectedVersion":%d,
                  "reasonCode":"%s",
                  "decisionNote":"admin@example.com %s token=decision-secret",
                  "tenantId":"tenant-forged",
                  "actorId":"actor-forged",
                  "evidenceSource":"AFTER_SALES_IDEMPOTENCY_QUERY"
                }
                """.formatted(expectedVersion, reasonCode, PRIVATE_PHONE);
    }

    private String confirmBody(long expectedVersion) {
        return """
                {
                  "expectedVersion":%d,
                  "reasonCode":"REMOTE_TICKET_VERIFIED",
                  "decisionNote":"admin@example.com %s token=decision-secret",
                  "tenantId":"tenant-forged",
                  "actorId":"actor-forged",
                  "evidenceSource":"NONE",
                  "result":{
                    "ticketId":"AS-20-4",
                    "status":"PENDING",
                    "createdAt":"2026-07-13T08:00:00+08:00"
                  }
                }
                """.formatted(expectedVersion, PRIVATE_PHONE);
    }

    private String adminToken() {
        return tokenService.issueDemoUserTokens().stream()
                .filter(token -> ADMIN_USER_ID.equals(token.userId()))
                .findFirst()
                .orElseThrow()
                .token();
    }

    private RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private void assertForgedIdentityWasIgnored(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("tenant-forged"));
        assertFalse(json.contains("actor-forged"));
        assertFalse(json.contains("AFTER_SALES_IDEMPOTENCY_QUERY"));
    }

    private void assertNoRecordSecrets(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        for (String secret : List.of(
                PRIVATE_USER_ID,
                PRIVATE_CONVERSATION_ID,
                PRIVATE_CUSTOMER_ID,
                PRIVATE_PHONE,
                PRIVATE_TOKEN,
                "private request title",
                "private request description")) {
            assertFalse(json.contains(secret), () -> "ADMIN response leaked: " + secret);
        }
        assertFalse(json.contains("\"tenantId\""));
        assertFalse(json.contains("\"userId\""));
        assertFalse(json.contains("\"conversationId\""));
        assertFalse(json.contains("\"customerId\""));
        assertFalse(json.contains("\"idempotencyKey\""));
        assertFalse(json.contains("\"request\""));
    }
}
