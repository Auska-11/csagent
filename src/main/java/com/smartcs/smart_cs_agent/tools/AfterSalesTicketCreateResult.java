package com.smartcs.smart_cs_agent.tools;

/**
 * 售后工单平台创建成功后，Agent 侧真正关心的最小结果。
 *
 * 后端平台会返回很多 SLA 字段，例如响应截止时间、解决截止时间、派单人等；
 * 第一版联调里，Agent 只需要把工单号、状态和创建时间返回给用户与 Trace。
 */
public record AfterSalesTicketCreateResult(
        String ticketId,
        String status,
        String createdAt
) {
}
