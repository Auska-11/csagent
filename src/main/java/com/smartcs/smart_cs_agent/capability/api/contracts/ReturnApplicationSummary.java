package com.smartcs.smart_cs_agent.capability.api.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * {@code return.apply} 成功时返回的稳定输出契约。
 *
 * <p>领域服务保留 ReturnRecord 等内部模型，Provider 在边界处转换为本 DTO。幂等记录或远程 MCP
 * 反序列化后会得到普通 Map，所以 {@link #fromMap(Object)} 同时支持 snake_case 和 camelCase。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ReturnApplicationSummary {

    private final boolean submitted;
    private final String returnId;
    private final String orderId;
    private final String productName;
    private final String reason;
    private final String status;
    private final String expectedReviewTime;

    public ReturnApplicationSummary(boolean submitted,
                                    String returnId,
                                    String orderId,
                                    String productName,
                                    String reason,
                                    String status,
                                    String expectedReviewTime) {
        this.submitted = submitted;
        this.returnId = safeText(returnId);
        this.orderId = safeText(orderId);
        this.productName = safeText(productName);
        this.reason = safeText(reason);
        this.status = safeText(status);
        this.expectedReviewTime = safeText(expectedReviewTime);
    }

    public static ReturnApplicationSummary fromMap(Object value) {
        if (value instanceof ReturnApplicationSummary summary) {
            return summary;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new ReturnApplicationSummary(
                booleanValue(map, "submitted"),
                text(map, "return_id", "returnId"),
                text(map, "order_id", "orderId"),
                text(map, "product_name", "productName"),
                text(map, "reason"),
                text(map, "status"),
                text(map, "expected_review_time", "expectedReviewTime")
        );
    }

    @JsonProperty("submitted")
    public boolean isSubmitted() {
        return submitted;
    }

    @JsonProperty("return_id")
    public String getReturnId() {
        return returnId;
    }

    @JsonProperty("order_id")
    public String getOrderId() {
        return orderId;
    }

    @JsonProperty("product_name")
    public String getProductName() {
        return productName;
    }

    @JsonProperty("reason")
    public String getReason() {
        return reason;
    }

    @JsonProperty("status")
    public String getStatus() {
        return status;
    }

    @JsonProperty("expected_review_time")
    public String getExpectedReviewTime() {
        return expectedReviewTime;
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static boolean booleanValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
