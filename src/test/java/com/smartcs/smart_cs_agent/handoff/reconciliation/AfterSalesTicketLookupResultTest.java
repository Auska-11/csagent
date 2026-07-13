package com.smartcs.smart_cs_agent.handoff.reconciliation;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 三态结果的不变量测试，重点防止 empty 同时表示“不存在”和“没查成”。 */
class AfterSalesTicketLookupResultTest {

    @Test
    void shouldBuildThreeUnambiguousOutcomes() {
        AfterSalesTicketCreateResult ticket = new AfterSalesTicketCreateResult(
                "AS-101", "PENDING", "2026-07-13T00:00:00Z"
        );

        AfterSalesTicketLookupResult found = AfterSalesTicketLookupResult.found(ticket);
        AfterSalesTicketLookupResult notFound = AfterSalesTicketLookupResult.notFound();
        AfterSalesTicketLookupResult unavailable = AfterSalesTicketLookupResult.unavailable(
                AfterSalesTicketLookupUnavailableReason.QUERY_NOT_SUPPORTED
        );

        assertEquals(AfterSalesTicketLookupOutcome.FOUND, found.outcome());
        assertEquals(ticket, found.ticket());
        assertNull(found.unavailableReason());
        assertEquals(AfterSalesTicketLookupOutcome.NOT_FOUND, notFound.outcome());
        assertNull(notFound.ticket());
        assertNull(notFound.unavailableReason());
        assertEquals(AfterSalesTicketLookupOutcome.UNAVAILABLE, unavailable.outcome());
        assertNull(unavailable.ticket());
        assertEquals(
                AfterSalesTicketLookupUnavailableReason.QUERY_NOT_SUPPORTED,
                unavailable.unavailableReason()
        );
    }

    @Test
    void shouldRejectImpossibleOutcomePayloadCombinations() {
        AfterSalesTicketCreateResult ticket = new AfterSalesTicketCreateResult(
                "AS-101", "PENDING", "2026-07-13T00:00:00Z"
        );

        assertThrows(NullPointerException.class, () ->
                new AfterSalesTicketLookupResult(AfterSalesTicketLookupOutcome.FOUND, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new AfterSalesTicketLookupResult(
                        AfterSalesTicketLookupOutcome.NOT_FOUND,
                        ticket,
                        null
                ));
        assertThrows(IllegalArgumentException.class, () ->
                new AfterSalesTicketLookupResult(
                        AfterSalesTicketLookupOutcome.UNAVAILABLE,
                        null,
                        null
                ));
    }

    @Test
    void shouldNotPrintFoundTicketInDefaultLogRepresentation() {
        AfterSalesTicketLookupResult found = AfterSalesTicketLookupResult.found(
                new AfterSalesTicketCreateResult(
                        "AS-PRIVATE-101", "PENDING", "2026-07-13T00:00:00Z"
                )
        );

        assertFalse(found.toString().contains("AS-PRIVATE-101"));
        assertEquals(
                "AfterSalesTicketLookupResult[outcome=FOUND, unavailableReason=null]",
                found.toString()
        );
    }
}
