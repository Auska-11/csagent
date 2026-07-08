package com.smartcs.smart_cs_agent.tools;

/**
 * Agent 调用售后工单平台时的创建工单请求。
 *
 * 注意：这个类不是直接暴露给前端的接口 DTO，而是 Agent 内部和后端工单平台之间的契约。
 */
public record AfterSalesTicketCreateRequest(
        String title,
        String description,
        String customerId,
        String productLine,
        String issueType,
        String priority,
        String idempotencyKey
) {
}
