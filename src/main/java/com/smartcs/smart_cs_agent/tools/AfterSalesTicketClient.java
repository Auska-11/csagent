package com.smartcs.smart_cs_agent.tools;

/**
 * 售后工单平台客户端接口。
 *
 * 【为什么先抽接口？】
 * HandoffTools 只关心“创建工单”这个业务动作，不应该关心 HTTP 细节。
 * 测试时也可以用一个假的 Client，不需要真的启动 demo 后端。
 */
public interface AfterSalesTicketClient {

    AfterSalesTicketCreateResult createTicket(AfterSalesTicketCreateRequest request);
}
