package com.smartcs.smart_cs_agent.tools;

/**
 * 售后工单平台客户端接口。
 *
 * 【为什么先抽接口？】
 * HandoffTools 只关心“创建工单”这个业务动作，不应该关心 HTTP 细节。
 * 测试时也可以用一个假的 Client，不需要真的启动 demo 后端。
 */
public interface AfterSalesTicketClient {

    /**
     * 在指定租户中创建或幂等取得工单。
     *
     * <p>tenantId 必须来自服务端执行上下文，不能来自 LLM 参数或请求正文。把它放进 Port 方法签名，
     * 可以防止 HTTP 适配器长期使用一份全局固定租户配置，造成工单写入错误租户。</p>
     */
    AfterSalesTicketCreateResult createTicket(String tenantId,
                                               AfterSalesTicketCreateRequest request);

    /**
     * v1 变体：携带可信 conversationId，供需要“原商品/原买家/原会话”坐标的集成客户端
     * （SLA v1）从会话标识解析渠道坐标。默认委托旧签名，保证旧实现与旧测试无需改动。
     */
    default AfterSalesTicketCreateResult createTicket(String tenantId,
                                                       String conversationId,
                                                       AfterSalesTicketCreateRequest request) {
        return createTicket(tenantId, request);
    }
}
