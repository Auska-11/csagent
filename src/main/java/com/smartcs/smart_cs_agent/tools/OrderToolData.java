package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;

/**
 * 订单工具的结构化输出 DTO。
 *
 * 【为什么不用 Map？】
 * Map 虽然灵活，但字段名、字段类型都靠约定，写错了编译器也发现不了。
 * DTO 把工具输出变成明确契约：代码里能直接 getOrderId()，JSON 里仍然输出 order_id。
 */
public class OrderToolData {

    private final boolean found;
    private final String orderId;
    private final String userId;
    private final String productId;
    private final String productName;
    private final String status;
    private final String trackingNo;

    private OrderToolData(boolean found,
                          String orderId,
                          String userId,
                          String productId,
                          String productName,
                          String status,
                          String trackingNo) {
        this.found = found;
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.productName = productName;
        this.status = status;
        this.trackingNo = trackingNo;
    }

    public static OrderToolData found(Order order, Product product) {
        return new OrderToolData(
                true,
                order.getOrderId(),
                order.getUserId(),
                order.getProductId(),
                product != null ? product.getName() : "Unknown Product",
                order.getStatus(),
                order.getTrackingNo()
        );
    }

    public static OrderToolData notFound(String orderId) {
        return new OrderToolData(false, orderId, null, null, null, null, null);
    }

    @JsonProperty("found")
    public boolean isFound() {
        return found;
    }

    @JsonProperty("order_id")
    public String getOrderId() {
        return orderId;
    }

    @JsonProperty("user_id")
    public String getUserId() {
        return userId;
    }

    @JsonProperty("product_id")
    public String getProductId() {
        return productId;
    }

    @JsonProperty("product_name")
    public String getProductName() {
        return productName;
    }

    @JsonProperty("status")
    public String getStatus() {
        return status;
    }

    @JsonProperty("tracking_no")
    public String getTrackingNo() {
        return trackingNo;
    }
}
