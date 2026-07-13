package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 待人工复核 Outbox 的 ADMIN HTTP 入口。
 *
 * <p>URL 和请求体都不接收 tenantId 或 actorId。二者只能来自已经通过 JWT 校验的 Principal；真正的
 * 角色授权由 SecurityFilterChain 完成，Service 还会做一次防御性检查。</p>
 */
@RestController
@RequestMapping("/api/admin/pending-handoffs")
public class PendingHandoffAdminController {

    private final PendingHandoffAdminService adminService;

    public PendingHandoffAdminController(PendingHandoffAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public PendingHandoffAdminListResponse findReviewPage(
            @RequestParam(defaultValue = "NEEDS_MANUAL_REVIEW") String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer limit,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.findReviewPage(principal, status, cursor, limit);
    }

    @GetMapping("/{outboxId}")
    public PendingHandoffAdminDetailResponse findDetail(
            @PathVariable String outboxId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.findDetail(principal, outboxId);
    }

    @GetMapping("/{outboxId}/audit-events")
    public PendingHandoffAdminAuditPageResponse findAuditPage(
            @PathVariable String outboxId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer limit,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.findAuditPage(principal, outboxId, cursor, limit);
    }

    @PostMapping("/{outboxId}/retry")
    public PendingHandoffAdminDecisionResponse retry(
            @PathVariable String outboxId,
            @Valid @RequestBody PendingHandoffAdminDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.retry(principal, outboxId, request);
    }

    @PostMapping("/{outboxId}/confirm")
    public PendingHandoffAdminDecisionResponse confirm(
            @PathVariable String outboxId,
            @Valid @RequestBody PendingHandoffAdminConfirmRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.confirm(principal, outboxId, request);
    }

    /** 使用服务端查询 Port 核对原始幂等键；请求体不能提供或覆盖远端工单结果。 */
    @PostMapping("/{outboxId}/reconcile")
    public PendingHandoffAdminDecisionResponse reconcile(
            @PathVariable String outboxId,
            @Valid @RequestBody PendingHandoffAdminDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.reconcile(principal, outboxId, request);
    }

    @PostMapping("/{outboxId}/close")
    public PendingHandoffAdminDecisionResponse close(
            @PathVariable String outboxId,
            @Valid @RequestBody PendingHandoffAdminDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return adminService.close(principal, outboxId, request);
    }
}
