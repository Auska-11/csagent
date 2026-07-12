package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.Map;

/**
 * 订单查询能力返回的稳定摘要契约。
 *
 * <p>职责：在 Workflow 与 Provider 之间传递订单号、所属用户、商品、状态和物流信息。</p>
 *
 * <p>不负责：不查询数据库、不判断当前用户能否查看该订单，也不组织客服话术。权限校验属于
 * Provider/领域边界，话术属于 Workflow。</p>
 *
 * <p>为什么不直接使用 {@code tools.OrderToolData}：tools 包是 Legacy 实现细节；远程 MCP 返回的
 * 又是 JSON Map。稳定契约把两种来源统一起来，使 Workflow 切换 Provider 时不需要修改。</p>
 */
public final class OrderSummary {

    private final String orderId;
    private final String userId;
    private final String productId;
    private final String productName;
    private final String status;
    private final String trackingNo;

    public OrderSummary(String orderId,
                        String userId,
                        String productId,
                        String productName,
                        String status,
                        String trackingNo) {
        this.orderId = safeText(orderId);
        this.userId = safeText(userId);
        this.productId = safeText(productId);
        this.productName = safeText(productName);
        this.status = safeText(status);
        this.trackingNo = safeText(trackingNo);
    }

    /**
     * 将远程 MCP 反序列化后的 Map 恢复为强类型契约。
     *
     * <p>同时兼容 snake_case 和 camelCase，是因为不同 MCP Server 的 JSON 命名策略可能不同。</p>
     */
    public static OrderSummary fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new OrderSummary(
                text(map, "order_id", "orderId"),
                text(map, "user_id", "userId"),
                text(map, "product_id", "productId"),
                text(map, "product_name", "productName"),
                text(map, "status"),
                text(map, "tracking_no", "trackingNo")
        );
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getStatus() {
        return status;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public boolean hasTrackingNo() {
        return !trackingNo.isBlank();
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
