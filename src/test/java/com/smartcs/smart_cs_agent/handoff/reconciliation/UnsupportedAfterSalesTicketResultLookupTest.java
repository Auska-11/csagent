package com.smartcs.smart_cs_agent.handoff.reconciliation;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 当前外部契约缺失时必须 fail-closed，不能把未知伪装成 NOT_FOUND。 */
class UnsupportedAfterSalesTicketResultLookupTest {

    private final UnsupportedAfterSalesTicketResultLookup lookup =
            new UnsupportedAfterSalesTicketResultLookup();

    @Test
    void shouldReturnExplicitUnsupportedInsteadOfNotFound() {
        AfterSalesTicketLookupResult result = lookup.findByIdempotencyKey(
                new PendingHandoffKey("tenant-a", "idempotency-secret")
        );

        assertEquals(AfterSalesTicketLookupOutcome.UNAVAILABLE, result.outcome());
        assertEquals(
                AfterSalesTicketLookupUnavailableReason.QUERY_NOT_SUPPORTED,
                result.unavailableReason()
        );
        assertNull(result.ticket());
    }

    @Test
    void shouldRejectMissingCompositeKeyBeforeReturningAnOutcome() {
        assertThrows(NullPointerException.class, () -> lookup.findByIdempotencyKey(null));
    }
}
