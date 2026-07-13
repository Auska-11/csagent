package com.smartcs.smart_cs_agent.handoff.reconciliation;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;

import java.util.Objects;

/**
 * 售后幂等查询的不可变结果。
 *
 * <p>使用一个带不变量的结果对象，而不是 {@code Optional<Ticket>}：Optional.empty() 无法说明“权威查询
 * 确认不存在”还是“网络失败根本没查到”，而这两个结论会触发完全不同的人工决定。</p>
 */
public record AfterSalesTicketLookupResult(
        AfterSalesTicketLookupOutcome outcome,
        AfterSalesTicketCreateResult ticket,
        AfterSalesTicketLookupUnavailableReason unavailableReason
) {

    public AfterSalesTicketLookupResult {
        Objects.requireNonNull(outcome, "outcome cannot be null");
        switch (outcome) {
            case FOUND -> {
                Objects.requireNonNull(ticket, "FOUND lookup requires a ticket");
                if (unavailableReason != null) {
                    throw new IllegalArgumentException("FOUND lookup cannot have an unavailable reason");
                }
            }
            case NOT_FOUND -> {
                if (ticket != null || unavailableReason != null) {
                    throw new IllegalArgumentException("NOT_FOUND lookup cannot carry ticket or failure data");
                }
            }
            case UNAVAILABLE -> {
                if (ticket != null || unavailableReason == null) {
                    throw new IllegalArgumentException("UNAVAILABLE lookup requires only a safe reason");
                }
            }
        }
    }

    public static AfterSalesTicketLookupResult found(AfterSalesTicketCreateResult ticket) {
        return new AfterSalesTicketLookupResult(
                AfterSalesTicketLookupOutcome.FOUND,
                ticket,
                null
        );
    }

    public static AfterSalesTicketLookupResult notFound() {
        return new AfterSalesTicketLookupResult(
                AfterSalesTicketLookupOutcome.NOT_FOUND,
                null,
                null
        );
    }

    public static AfterSalesTicketLookupResult unavailable(
            AfterSalesTicketLookupUnavailableReason reason) {
        return new AfterSalesTicketLookupResult(
                AfterSalesTicketLookupOutcome.UNAVAILABLE,
                null,
                Objects.requireNonNull(reason, "reason cannot be null")
        );
    }

    /** 默认 record.toString() 会在 FOUND 时打印远端工单；日志只需要结论和安全原因。 */
    @Override
    public String toString() {
        return "AfterSalesTicketLookupResult["
                + "outcome=" + outcome
                + ", unavailableReason=" + unavailableReason
                + ']';
    }
}
