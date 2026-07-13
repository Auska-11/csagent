package com.smartcs.smart_cs_agent.handoff.reconciliation;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 当前售后平台不支持结果查询时使用的显式 Adapter。
 *
 * <p>它不访问网络，也绝不返回 NOT_FOUND。以后下游增加受权威保护的查询接口后，可以新增条件为
 * {@code http} 的 Adapter 并切换配置；Controller、Service、审计和 Store 都不需要修改。</p>
 */
@Component
@ConditionalOnProperty(
        name = "smartcs.handoff.after-sales.result-query-mode",
        havingValue = "unavailable",
        matchIfMissing = true
)
public class UnsupportedAfterSalesTicketResultLookup implements AfterSalesTicketResultLookup {

    @Override
    public AfterSalesTicketLookupResult findByIdempotencyKey(PendingHandoffKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        return AfterSalesTicketLookupResult.unavailable(
                AfterSalesTicketLookupUnavailableReason.QUERY_NOT_SUPPORTED
        );
    }
}
