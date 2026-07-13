package com.smartcs.smart_cs_agent.handoff.reconciliation;

/** 权威售后查询已成功执行，但当前租户和幂等键下没有对应工单。 */
public class AfterSalesTicketLookupNotFoundException extends RuntimeException {

    public AfterSalesTicketLookupNotFoundException() {
        super("After-sales ticket was not found by idempotency key");
    }
}
