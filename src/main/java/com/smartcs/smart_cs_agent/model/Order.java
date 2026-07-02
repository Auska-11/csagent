package com.smartcs.smart_cs_agent.model;

/**
 * 订单实体类
 * 对应数据库中的订单表
 *
 * 关联关系：
 * - userId 关联 User 表
 * - productId 关联 Product 表
 */
public class Order {

    private String orderId;      // 订单号
    private String userId;       // 用户ID（外键）
    private String productId;    // 产品ID（外键）
    private String status;       // 订单状态：待发货、已发货、已签收
    private String trackingNo;   // 物流单号

    public Order(String orderId, String userId, String productId, String status, String trackingNo) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.status = status;
        this.trackingNo = trackingNo;
    }

    // Getter 方法
    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getProductId() { return productId; }
    public String getStatus() { return status; }
    public String getTrackingNo() { return trackingNo; }

    @Override
    public String toString() {
        return "Order{" +
               "orderId='" + orderId + '\'' +
               ", userId='" + userId + '\'' +
               ", productId='" + productId + '\'' +
               ", status='" + status + '\'' +
               ", trackingNo='" + trackingNo + '\'' +
               '}';
    }
}