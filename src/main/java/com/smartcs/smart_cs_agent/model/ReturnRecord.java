package com.smartcs.smart_cs_agent.model;

/**
 * 退换货记录实体类
 * 对应数据库中的退换货表
 *
 * 关联关系：
 * - orderId 关联 Order 表
 */
public class ReturnRecord {

    private String returnId;   // 退换单号
    private String orderId;    // 订单号（外键）
    private String reason;     // 退换原因
    private String status;     // 状态：审核中、审核通过、已寄回、已完成

    public ReturnRecord(String returnId, String orderId, String reason, String status) {
        this.returnId = returnId;
        this.orderId = orderId;
        this.reason = reason;
        this.status = status;
    }

    // Getter 方法
    public String getReturnId() { return returnId; }
    public String getOrderId() { return orderId; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }

    @Override
    public String toString() {
        return "ReturnRecord{" +
               "returnId='" + returnId + '\'' +
               ", orderId='" + orderId + '\'' +
               ", reason='" + reason + '\'' +
               ", status='" + status + '\'' +
               '}';
    }
}