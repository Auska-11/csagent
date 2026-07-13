package com.smartcs.smart_cs_agent.handoff.reconciliation;

import java.util.Objects;

/** 当前无法得到权威售后查询结论；异常不保存租户、幂等键、URL 或底层异常。 */
public class AfterSalesTicketLookupUnavailableException extends RuntimeException {

    private final AfterSalesTicketLookupUnavailableReason reason;

    public AfterSalesTicketLookupUnavailableException(
            AfterSalesTicketLookupUnavailableReason reason) {
        super("After-sales ticket result query is unavailable");
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
    }

    public AfterSalesTicketLookupUnavailableReason getReason() {
        return reason;
    }
}
