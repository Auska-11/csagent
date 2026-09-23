package com.smartcs.smart_cs_agent.handoff.sla;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联调对账用的 ADMIN 只读端点：按 requestId 查看建单关联与当前生效结果。
 *
 * <p>不暴露回调令牌等敏感配置；路径 /api/admin/sla-results/** 在安全链路中限定 ADMIN。</p>
 */
@RestController
@RequestMapping("/api/admin/sla-results")
public class SlaResultAdminController {

    private final SlaIntegrationStore slaIntegrationStore;

    public SlaResultAdminController(SlaIntegrationStore slaIntegrationStore) {
        this.slaIntegrationStore = slaIntegrationStore;
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> findByRequestId(
            @PathVariable("requestId") String requestId) {
        if (requestId == null || requestId.isBlank() || !SlaV1RequestIds.isValid(requestId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "INVALID_REQUEST"
            ));
        }
        SlaWorkItemAssociation association = slaIntegrationStore
                .findAssociationByRequestId(requestId)
                .orElse(null);
        SlaResultSnapshot result = slaIntegrationStore
                .findCurrentResult(requestId)
                .orElse(null);
        if (association == null && result == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "NOT_FOUND"
            ));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (association != null) {
            Map<String, Object> associationView = new LinkedHashMap<>();
            associationView.put("tenantId", association.tenantId());
            associationView.put("idempotencyKey", association.idempotencyKey());
            associationView.put("channel", association.channel());
            associationView.put("sellerAccountId", association.sellerAccountId());
            associationView.put("itemId", association.itemId());
            associationView.put("conversationId", association.conversationId());
            associationView.put("buyerId", association.buyerId());
            associationView.put("workItemId", association.workItemId());
            associationView.put("createdAt", association.createdAt());
            associationView.put("updatedAt", association.updatedAt());
            data.put("association", associationView);
        }
        data.put("currentResult", result);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return ResponseEntity.ok(response);
    }
}
