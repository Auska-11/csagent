package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过 HTTP 调用售后工单平台的客户端。
 *
 * 【对应后端项目】
 * C:\Users\feasycom\Desktop\demo\after-sales-sla-platform
 *
 * 【调用接口】
 * POST /api/tickets
 *
 * 这里保持很薄：只负责组装 HTTP 请求、解析统一响应结构，不做复杂业务判断。
 * 业务判断放在 HandoffTools 里，便于新手阅读。
 */
@Component
public class HttpAfterSalesTicketClient implements AfterSalesTicketClient {

    private final HandoffProperties properties;
    private final RestClient restClient;

    @Autowired
    public HttpAfterSalesTicketClient(HandoffProperties properties) {
        this.properties = properties;
        HandoffProperties.AfterSales afterSales = properties.getAfterSales();
        this.restClient = BoundedRestClientFactory.create(
                normalizeBaseUrl(afterSales.getBaseUrl()),
                afterSales.getConnectTimeoutMs(),
                afterSales.getReadTimeoutMs()
        );
    }

    /**
     * 单元测试构造函数。
     *
     * <p>传入的 Builder 可能已经绑定 MockRestServiceServer，因此这里不能再覆盖 requestFactory。
     * 生产超时由上面的 Spring 构造函数和真实慢 HTTP 测试验证。</p>
     */
    HttpAfterSalesTicketClient(HandoffProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(properties.getAfterSales().getBaseUrl()))
                .build();
    }

    @Override
    public AfterSalesTicketCreateResult createTicket(AfterSalesTicketCreateRequest request) {
        try {
            TicketApiResponse response = restClient.post()
                    .uri("/api/tickets")
                    .header("X-Tenant-Id", properties.getAfterSales().getTenantId())
                    .header("X-User-Id", properties.getAfterSales().getAgentUserId())
                    .header("X-User-Role", properties.getAfterSales().getAgentUserRole())
                    .header("Idempotency-Key", request.idempotencyKey())
                    .body(toRequestBody(request))
                    .retrieve()
                    .body(TicketApiResponse.class);

            if (response == null || !response.success() || response.data() == null) {
                String message = response == null ? "empty response" : response.message();
                throw new IllegalStateException("售后工单平台创建工单失败：" + message);
            }

            return toCreateResult(response.data());
        } catch (RestClientException e) {
            throw new IllegalStateException("调用售后工单平台失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> toRequestBody(AfterSalesTicketCreateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", request.title());
        body.put("description", request.description());
        body.put("customerId", request.customerId());
        body.put("productLine", request.productLine());
        body.put("issueType", request.issueType());
        body.put("priority", request.priority());
        return body;
    }

    private AfterSalesTicketCreateResult toCreateResult(TicketPayload data) {
        String ticketId = data.id() == null ? "AS-UNKNOWN" : "AS-" + data.id();
        String status = data.status() == null || data.status().isBlank()
                ? "PENDING"
                : data.status();
        String createdAt = data.createdAt() == null
                ? LocalDateTime.now().toString()
                : data.createdAt().toString();

        return new AfterSalesTicketCreateResult(ticketId, status, createdAt);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8081";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    /**
     * demo 后端的统一响应结构：ApiResponse<TicketResponse>。
     * 只声明 Agent 当前要用的字段，其余字段通过 JsonIgnoreProperties 忽略。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TicketApiResponse(boolean success, TicketPayload data, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TicketPayload(Long id, String status, Instant createdAt) {
    }
}
