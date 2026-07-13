package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffVersionConflictException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.security.HttpSecurityConfiguration;
import com.smartcs.smart_cs_agent.security.SecurityErrorWriter;
import com.smartcs.smart_cs_agent.security.SecurityProperties;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 20.4 Controller 的 JSON、Bean Validation、RBAC 与安全异常契约测试。 */
@WebMvcTest(PendingHandoffAdminController.class)
@Import({HttpSecurityConfiguration.class, SecurityProperties.class, SecurityErrorWriter.class})
class PendingHandoffAdminControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PendingHandoffAdminService adminService;

    @Test
    void shouldRejectAnonymousAndCustomerBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/admin/pending-handoffs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/admin/pending-handoffs").with(customer("tenant-a", "customer-a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        verifyNoInteractions(adminService);
    }

    @Test
    void shouldPassVerifiedAdminPrincipalAndSerializeListContract() throws Exception {
        when(adminService.findReviewPage(any(), eq("CONFIRMED"), isNull(), eq(5)))
                .thenReturn(new PendingHandoffAdminListResponse(
                        List.of(new PendingHandoffAdminListItemResponse(
                                "outbox-1",
                                PendingHandoffStatus.CONFIRMED,
                                "COMPLAINT",
                                "NONE",
                                2,
                                null,
                                4,
                                100,
                                200
                        )),
                        null,
                        false
                ));

        mockMvc.perform(get("/api/admin/pending-handoffs")
                        .param("status", "CONFIRMED")
                        .param("limit", "5")
                        .with(admin("tenant-a", "admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outboxId").value("outbox-1"))
                .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].handoffReasonCode").value("COMPLAINT"))
                .andExpect(jsonPath("$.items[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(false));

        ArgumentCaptor<AuthenticatedPrincipal> principal =
                ArgumentCaptor.forClass(AuthenticatedPrincipal.class);
        verify(adminService).findReviewPage(principal.capture(), eq("CONFIRMED"), isNull(), eq(5));
        assertEquals("tenant-a", principal.getValue().getTenantId());
        assertEquals("admin-a", principal.getValue().getUserId());
    }

    @Test
    void shouldIgnoreForgedIdentityFieldsAndReturnOnlyProjectedDecision() throws Exception {
        when(adminService.retry(any(), eq("outbox-1"), any()))
                .thenReturn(decisionResponse());

        MvcResult result = mockMvc.perform(post("/api/admin/pending-handoffs/outbox-1/retry")
                        .with(admin("tenant-trusted", "admin-trusted"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":3,
                                  "reasonCode":"RETRY_APPROVED",
                                  "decisionNote":"safe note",
                                  "tenantId":"tenant-forged",
                                  "actorId":"actor-forged",
                                  "evidenceSource":"AFTER_SALES_IDEMPOTENCY_QUERY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.outboxId").value("outbox-1"))
                .andExpect(jsonPath("$.auditEvent.action").value("RETRY"))
                .andReturn();

        ArgumentCaptor<AuthenticatedPrincipal> principal =
                ArgumentCaptor.forClass(AuthenticatedPrincipal.class);
        ArgumentCaptor<PendingHandoffAdminDecisionRequest> request =
                ArgumentCaptor.forClass(PendingHandoffAdminDecisionRequest.class);
        verify(adminService).retry(principal.capture(), eq("outbox-1"), request.capture());
        assertEquals("tenant-trusted", principal.getValue().getTenantId());
        assertEquals("admin-trusted", principal.getValue().getUserId());
        assertEquals("RETRY_APPROVED", request.getValue().reasonCode());

        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("tenant-forged"));
        assertFalse(json.contains("actor-forged"));
        assertFalse(json.contains("idempotencyKey"));
    }

    @Test
    void shouldRejectInvalidParametersBodiesAndCursorWithStableErrors() throws Exception {
        mockMvc.perform(get("/api/admin/pending-handoffs")
                        .param("limit", "not-a-number")
                        .with(admin("tenant-a", "admin-a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_PARAMETER"));

        mockMvc.perform(post("/api/admin/pending-handoffs/outbox-1/retry")
                        .with(admin("tenant-a", "admin-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":-1,"reasonCode":"lowercase","decisionNote":"note"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/admin/pending-handoffs/outbox-1/confirm")
                        .with(admin("tenant-a", "admin-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,"reasonCode":"REMOTE_VERIFIED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        when(adminService.findReviewPage(any(), anyString(), eq("broken"), anyInt()))
                .thenThrow(new PendingHandoffAdminCursorException());
        mockMvc.perform(get("/api/admin/pending-handoffs")
                        .param("cursor", "broken")
                        .with(admin("tenant-a", "admin-a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PENDING_HANDOFF_CURSOR"));
    }

    @Test
    void shouldMapDomainConflictsAndUnexpectedFailureWithoutLeakingDetails() throws Exception {
        when(adminService.findDetail(any(), eq("missing")))
                .thenThrow(new PendingHandoffReviewNotFoundException());
        when(adminService.retry(any(), eq("stale"), any()))
                .thenThrow(new PendingHandoffVersionConflictException());
        when(adminService.close(any(), eq("closed"), any()))
                .thenThrow(new PendingHandoffStateConflictException(PendingHandoffStatus.CONFIRMED));
        when(adminService.findDetail(any(), eq("broken")))
                .thenThrow(new IllegalStateException("jdbc password=must-not-reach-json"));

        mockMvc.perform(get("/api/admin/pending-handoffs/missing")
                        .with(admin("tenant-a", "admin-a")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PENDING_HANDOFF_NOT_FOUND"));
        mockMvc.perform(post("/api/admin/pending-handoffs/stale/retry")
                        .with(admin("tenant-a", "admin-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDecisionBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PENDING_HANDOFF_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/admin/pending-handoffs/closed/close")
                        .with(admin("tenant-a", "admin-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDecisionBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PENDING_HANDOFF_STATE_CONFLICT"));

        MvcResult unexpected = mockMvc.perform(get("/api/admin/pending-handoffs/broken")
                        .with(admin("tenant-a", "admin-a")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn();
        assertFalse(unexpected.getResponse().getContentAsString().contains("password"));
    }

    private PendingHandoffAdminDecisionResponse decisionResponse() {
        PendingHandoffAdminDetailResponse detail = new PendingHandoffAdminDetailResponse(
                "outbox-1",
                PendingHandoffStatus.RETRY_SCHEDULED,
                "COMPLAINT",
                "MANUAL_RETRY_REQUESTED",
                "safe preview",
                "general",
                "complaint",
                "HIGH",
                null,
                2,
                1_000L,
                4,
                100,
                1_000
        );
        PendingHandoffAdminAuditEventResponse audit = new PendingHandoffAdminAuditEventResponse(
                "audit-1",
                PendingHandoffManualAction.RETRY,
                "admin-trusted",
                PendingHandoffReviewAuthority.ADMIN,
                "RETRY_APPROVED",
                "safe note",
                PendingHandoffEvidenceSource.NONE,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                PendingHandoffStatus.RETRY_SCHEDULED,
                3,
                4,
                "SocketTimeoutException",
                2,
                1_000L,
                1_000
        );
        return new PendingHandoffAdminDecisionResponse(detail, audit);
    }

    private String validDecisionBody() {
        return """
                {"expectedVersion":3,"reasonCode":"ADMIN_DECISION","decisionNote":"safe note"}
                """;
    }

    private RequestPostProcessor admin(String tenantId, String actorId) {
        return authenticated(tenantId, actorId, SecurityRoles.ADMIN);
    }

    private RequestPostProcessor customer(String tenantId, String actorId) {
        return authenticated(tenantId, actorId, SecurityRoles.CUSTOMER);
    }

    private RequestPostProcessor authenticated(String tenantId, String actorId, String role) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.authenticated(
                tenantId, actorId, Set.of(role)
        );
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        "test-credentials",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
        return authentication(authentication);
    }
}
