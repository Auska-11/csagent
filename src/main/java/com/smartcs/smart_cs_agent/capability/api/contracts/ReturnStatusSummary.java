package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.Map;

/**
 * {@code return.lookup} 的稳定输出契约。
 *
 * <p>Workflow 只需要退货单号、关联订单、商品、原因和状态。订单所属用户只用于 Provider 内部
 * 权限校验，不进入本 DTO，避免身份字段被不必要地传到回复和 Trace。</p>
 */
public final class ReturnStatusSummary {

    private final String returnId;
    private final String orderId;
    private final String productName;
    private final String reason;
    private final String status;

    public ReturnStatusSummary(String returnId,
                               String orderId,
                               String productName,
                               String reason,
                               String status) {
        this.returnId = safeText(returnId);
        this.orderId = safeText(orderId);
        this.productName = safeText(productName);
        this.reason = safeText(reason);
        this.status = safeText(status);
    }

    /** 将远程 MCP JSON Map 恢复为稳定契约。 */
    public static ReturnStatusSummary fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new ReturnStatusSummary(
                text(map, "return_id", "returnId"),
                text(map, "order_id", "orderId"),
                text(map, "product_name", "productName"),
                text(map, "reason"),
                text(map, "status")
        );
    }

    public String getReturnId() {
        return returnId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getProductName() {
        return productName;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
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

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
