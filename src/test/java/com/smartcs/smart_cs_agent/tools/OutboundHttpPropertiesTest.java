package com.smartcs.smart_cs_agent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboundHttpPropertiesTest {

    @Test
    void shouldProvideSafePositiveTimeoutDefaults() {
        HandoffProperties handoff = new HandoffProperties();
        RagProperties rag = new RagProperties();

        assertEquals(3000, handoff.getAfterSales().getConnectTimeoutMs());
        assertEquals(10000, handoff.getAfterSales().getReadTimeoutMs());
        assertEquals(3000, rag.getEmbedding().getConnectTimeoutMs());
        assertEquals(10000, rag.getEmbedding().getReadTimeoutMs());
        assertEquals(3000, rag.getQdrant().getConnectTimeoutMs());
        assertEquals(10000, rag.getQdrant().getReadTimeoutMs());
    }

    @Test
    void shouldRejectZeroOrNegativeTimeoutsInsteadOfDisablingProtection() {
        HandoffProperties handoff = new HandoffProperties();
        RagProperties rag = new RagProperties();

        assertThrows(
                IllegalArgumentException.class,
                () -> handoff.getAfterSales().setConnectTimeoutMs(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> handoff.getAfterSales().setReadTimeoutMs(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> rag.getEmbedding().setConnectTimeoutMs(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> rag.getEmbedding().setReadTimeoutMs(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> rag.getQdrant().setConnectTimeoutMs(0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> rag.getQdrant().setReadTimeoutMs(-1)
        );
    }
}
