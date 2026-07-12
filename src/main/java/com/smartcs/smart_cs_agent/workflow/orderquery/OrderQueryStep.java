package com.smartcs.smart_cs_agent.workflow.orderquery;

/**
 * 订单查询 Workflow 的内部步骤。
 *
 * <p>查询属于只读操作，不需要确认状态；但仍要区分“等订单号”和“等待哪种 Capability 结果”，
 * 否则拿到 Observation 后无法判断应该按单个订单还是订单列表解析。</p>
 */
public enum OrderQueryStep {
    START,
    WAITING_ORDER_ID,
    LOOKING_UP_ORDER,
    LISTING_USER_ORDERS,
    COMPLETED,
    CANCELLED,
    FAILED
}
