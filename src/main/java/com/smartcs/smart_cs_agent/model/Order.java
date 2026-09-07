package com.smartcs.smart_cs_agent.model;

/**
 * 订单实体类
 * 对应数据库中的订单表
 *
 * 关联关系：
 * - tenantId 租户隔离边界；演示数据落在 default 租户
 * - userId 关联 User 表
 * - productId 关联 Product 表
 */
public class Order {

    /** 未显式指定租户时的归属，与认证层的默认租户保持一致。 */
    public static final String DEFAULT_TENANT_ID = "default";

    private String tenantId;    // 租户ID
    private String orderId;     // 订单号
    private String userId;      // 用户ID（外键）
    private String productId;   // 产品ID（外键）
    private String status;      // 订单状态：待发货、已发货、已签收
    private String trackingNo;  // 物流单号

    public Order(String orderId, String userId, String productId, String status, String trackingNo) {
        this(DEFAULT_TENANT_ID, orderId, userId, productId, status, trackingNo);
    }

    public Order(String tenantId, String orderId, String userId, String productId, String status, String trackingNo) {
        this.tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT_ID : tenantId.trim();
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.status = status;
        this.trackingNo = trackingNo;
    }

    // Getter 方法
    public String getTenantId() { return tenantId; }
    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getProductId() { return productId; }
    public String getStatus() { return status; }
    public String getTrackingNo() { return trackingNo; }

    @Override
    public String toString() {
        return "Order{" +
               "tenantId='" + tenantId + '\'' +
               ", orderId='" + orderId + '\'' +
               ", userId='" + userId + '\'' +
               ", productId='" + productId + '\'' +
               ", status='" + status + '\'' +
               ", trackingNo='" + trackingNo + '\'' +
               '}';
    }
}
