package com.smartcs.smart_cs_agent.mcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuditLogSanitizerTest {

    @Test
    void shouldRemoveControlCharactersAndBoundExternalIdentifier() {
        String raw = "order.lookup\nFORGED\t" + "x".repeat(200);

        String safe = McpAuditLogSanitizer.safeIdentifier(raw, "fallback");

        assertTrue(safe.codePointCount(0, safe.length())
                <= McpAuditLogSanitizer.MAX_IDENTIFIER_CODE_POINTS);
        assertFalse(safe.codePoints().anyMatch(Character::isISOControl));
        assertFalse(safe.contains("\n"));
        assertFalse(safe.contains("\t"));
    }

    @Test
    void shouldRedactKnownSecretsAndContactDetailsBeforeLoggingIdentifier() {
        String raw = "order.lookup password=do-not-log 13800138000";

        String safe = McpAuditLogSanitizer.safeIdentifier(raw, "fallback");

        assertFalse(safe.contains("do-not-log"));
        assertFalse(safe.contains("13800138000"));
        assertTrue(safe.contains("REDACTED"));
    }

    @Test
    void shouldUseStableOpaqueRequestReferenceInsteadOfRawRequestId() {
        String requestId = "request password=do-not-log 13800138000";

        String first = McpAuditLogSanitizer.requestReference(requestId);
        String second = McpAuditLogSanitizer.requestReference(requestId);

        assertEquals(first, second);
        assertEquals(16, first.length());
        assertFalse(first.contains("request"));
        assertFalse(first.contains("do-not-log"));
        assertEquals("none", McpAuditLogSanitizer.requestReference(""));
    }
}
