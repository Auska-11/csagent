package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuIdentity;
import com.smartcs.smart_cs_agent.handoff.sla.SlaIntegrationStore;
import com.smartcs.smart_cs_agent.handoff.sla.SlaV1Properties;
import com.smartcs.smart_cs_agent.handoff.sla.SlaV1RequestIds;
import com.smartcs.smart_cs_agent.handoff.sla.SlaWorkItemAssociation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SLA 平台 v1 集成客户端：服务账号 JWT + 幂等建单 + 超时对账。
 *
 * <p>契约 docs/31-seller-agent-integration-v1.md：POST /api/integrations/v1/work-items，
 * 头 Idempotency-Key 与体 requestId 一致；requestId 由 (tenantId, 内部幂等键) 全量 SHA-256
 * 加 wi-v1- 前缀派生，与 pending handoff 的本地关联在建单调用之前先行落库——回调可能先于
 * 创建响应到达。超时/5xx/429 先按 requestId 对账，404 可用原键重试（由既有恢复执行器执行）；
 * 400/401/403/409 属协议性拒绝，抛 {@link HandoffTicketCreationRejectedException}，
 * 由 HandoffTools/恢复执行器直接转入人工复核，不进入通用自动建单重试。</p>
 */
@Component
@Primary
@ConditionalOnProperty(name = "smartcs.handoff.mode", havingValue = "sla-v1")
public class SlaV1WorkItemClient implements AfterSalesTicketClient {

    static final String CHANNEL = "XIANYU";
    static final String WORK_ITEM_TYPE = "PRODUCT_CHECK";

    private static final String CREATE_PATH = "/api/integrations/v1/work-items";
    private static final String BY_REQUEST_PATH = "/api/integrations/v1/work-items/by-request/";

    private final SlaV1Properties properties;
    private final SlaIntegrationStore slaIntegrationStore;
    private final SlaV1AuthTokenService tokenService;
    private final RestClient restClient;

    @Autowired
    public SlaV1WorkItemClient(SlaV1Properties properties,
                                SlaIntegrationStore slaIntegrationStore,
                                SlaV1AuthTokenService tokenService) {
        this.properties = properties;
        this.slaIntegrationStore = slaIntegrationStore;
        this.tokenService = tokenService;
        this.restClient = BoundedRestClientFactory.create(
                normalizeBaseUrl(properties.getBaseUrl()),
                properties.getConnectTimeoutMs(),
                properties.getReadTimeoutMs()
        );
    }

    /**
     * 单元测试构造函数：Builder 可能已绑定 MockRestServiceServer，因此不能再覆盖 requestFactory。
     */
    SlaV1WorkItemClient(SlaV1Properties properties,
                        SlaIntegrationStore slaIntegrationStore,
                        SlaV1AuthTokenService tokenService,
                        RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.slaIntegrationStore = slaIntegrationStore;
        this.tokenService = tokenService;
        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(properties.getBaseUrl()))
                .build();
    }

    @Override
    public AfterSalesTicketCreateResult createTicket(String tenantId,
                                                     AfterSalesTicketCreateRequest request) {
        throw new IllegalStateException(
                "conversationId is required for SLA v1 work item creation"
        );
    }

    @Override
    public AfterSalesTicketCreateResult createTicket(String tenantId,
                                                     String conversationId,
                                                     AfterSalesTicketCreateRequest request) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        String sellerAccountId = properties.getSellerAccountId();
        if (sellerAccountId == null || sellerAccountId.isBlank()) {
            // 配置缺失是确定性失败：重试同样的请求不会成功，直接按拒绝分类转人工复核。
            throw new HandoffTicketCreationRejectedException(
                    "smartcs.handoff.sla-v1.seller-account-id is required in sla-v1 mode"
            );
        }
        XianyuIdentity.XianyuCoordinates coordinates = XianyuIdentity
                .parseConversationId(conversationId)
                .orElseThrow(() -> new HandoffTicketCreationRejectedException(
                        "SLA v1 work items require a xianyu_{buyerId}_{itemId} conversation"
                ));

        String normalizedTenantId = tenantId.trim();
        String requestId = SlaV1RequestIds.derive(normalizedTenantId, request.idempotencyKey());
        long now = System.currentTimeMillis();

        // 关联必须先于远端调用落库：进程在 POST 后崩溃时，回调仍能按 requestId 找到
        // 原商品、原买家、原会话并校验归属。
        slaIntegrationStore.recordAssociation(new SlaWorkItemAssociation(
                normalizedTenantId,
                request.idempotencyKey(),
                requestId,
                CHANNEL,
                sellerAccountId,
                coordinates.itemId(),
                conversationId,
                coordinates.buyerId(),
                null,
                now,
                now
        ));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("channel", CHANNEL);
        body.put("sellerAccountId", sellerAccountId);
        body.put("itemId", coordinates.itemId());
        body.put("conversationId", conversationId);
        body.put("buyerId", coordinates.buyerId());
        body.put("type", WORK_ITEM_TYPE);
        body.put("title", request.title());
        body.put("description", request.description());

        WorkItemCreatedResponse created;
        try {
            created = withAuthRetry(token -> restClient.post()
                    .uri(CREATE_PATH)
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(WorkItemCreatedResponse.class));
        } catch (HttpServerErrorException | ResourceAccessException possiblyUnknown) {
            // 超时、网络中断和 5xx：远端可能已建单，先按 requestId 对账。
            return reconcile(requestId);
        } catch (HttpClientErrorException.Unauthorized unauthorized) {
            // withAuthRetry 已刷新重试过一次仍 401：凭据或账号状态问题，重试无意义。
            throw new HandoffTicketCreationRejectedException(
                    "SLA v1 authentication failed after token refresh retry"
            );
        } catch (HttpClientErrorException rejected) {
            if (rejected.getStatusCode().value() == 429) {
                // 限流是唯一可重试的 4xx：保持结果未知语义，交给恢复执行器退避重试。
                throw new IllegalStateException(
                        "SLA v1 work item creation rate limited: HTTP 429"
                );
            }
            // 400/403/409 等协议性失败：同样的请求重试不会成功，标记为明确拒绝。
            throw new HandoffTicketCreationRejectedException(
                    "SLA v1 work item creation rejected: HTTP "
                            + rejected.getStatusCode().value()
                            + " " + safeEnvelopeMessage(rejected)
            );
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "SLA v1 work item creation failed: " + e.getClass().getSimpleName()
            );
        }

        if (created == null || !created.success() || created.data() == null) {
            String message = created == null ? "empty response" : created.message();
            throw new IllegalStateException("SLA v1 work item creation failed: " + message);
        }
        CreatedPayload data = created.data();
        if (data.workItemId() == null || data.workItemId() <= 0
                || data.status() == null || data.status().isBlank()
                || data.createdAt() == null) {
            throw new IllegalStateException("SLA v1 work item creation returned incomplete data");
        }
        slaIntegrationStore.attachWorkItemId(requestId, data.workItemId());
        return new AfterSalesTicketCreateResult(
                "AS-" + data.workItemId(),
                data.status().trim(),
                data.createdAt().toString()
        );
    }

    /**
     * 建单结果未知时按 requestId 对账：GET by-request 返回当前状态；
     * 404 只说明尚无可见已提交记录，抛出后由恢复执行器用原键原请求重试。
     */
    private AfterSalesTicketCreateResult reconcile(String requestId) {
        WorkItemDetailResponse detail;
        try {
            detail = withAuthRetry(token -> restClient.get()
                    .uri(BY_REQUEST_PATH + requestId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(WorkItemDetailResponse.class));
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new IllegalStateException(
                    "SLA v1 work item not visible by requestId; awaiting reconciliation"
            );
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "SLA v1 reconciliation query failed: " + e.getClass().getSimpleName()
            );
        }
        if (detail == null || !detail.success() || detail.data() == null) {
            throw new IllegalStateException("SLA v1 reconciliation returned no data");
        }
        DetailPayload data = detail.data();
        if (data.workItemId() == null || data.workItemId() <= 0
                || data.status() == null || data.status().isBlank()
                || data.createdAt() == null) {
            throw new IllegalStateException("SLA v1 reconciliation returned incomplete data");
        }
        slaIntegrationStore.attachWorkItemId(requestId, data.workItemId());
        return new AfterSalesTicketCreateResult(
                "AS-" + data.workItemId(),
                data.status().trim(),
                data.createdAt().toString()
        );
    }

    /**
     * 契约的 401 处理：作废缓存、重新登录一次、原请求原幂等键重放一次；二次 401 向上抛出。
     */
    private <T> T withAuthRetry(AuthenticatedCall<T> call) {
        String token = tokenService.accessToken();
        try {
            return call.apply(token);
        } catch (HttpClientErrorException.Unauthorized unauthorized) {
            tokenService.invalidate();
            return call.apply(tokenService.accessToken());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ERROR_ENVELOPE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String safeEnvelopeMessage(HttpClientErrorException e) {
        try {
            String raw = e.getResponseBodyAsString();
            // v1 错误外壳 message 是固定代码（INVALID_REQUEST/FORBIDDEN/...），可安全透出；
            // 解析失败则退回空串，避免把非预期响应体带进日志。
            com.fasterxml.jackson.databind.JsonNode node = ERROR_ENVELOPE_MAPPER.readTree(raw);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception parseFailure) {
            return "";
        }
        return "";
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8081";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    @FunctionalInterface
    private interface AuthenticatedCall<T> {
        T apply(String accessToken) throws RestClientException;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkItemCreatedResponse(boolean success, CreatedPayload data, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreatedPayload(
            Long workItemId,
            String requestId,
            String status,
            Instant createdAt,
            Integer resultVersion
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkItemDetailResponse(boolean success, DetailPayload data, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DetailPayload(
            Long workItemId,
            String requestId,
            String status,
            Instant createdAt,
            Integer resultVersion
    ) {
    }
}
