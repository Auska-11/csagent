package com.smartcs.smart_cs_agent.observability.trace;

import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trace 统一边界的递归脱敏、容量限制和不可变快照测试。 */
class TraceDataSanitizerTest {

    @Test
    void shouldRecursivelyRedactSensitiveValuesAndOmitRawFreeText() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("orderId", "ORD-001");
        nested.put("keyword", "蓝牙耳机");
        nested.put("apiKey", "canary-api-key");
        nested.put("userToken", "canary-user-token");
        nested.put("contactEmail", "buyer@example.com");
        nested.put("note", "联系电话 13800138000，token=canary-token");
        nested.put("keywords", List.of("耳机", "buyer@example.com"));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("message", "raw-message-canary");
        raw.put("promptPreview", "raw-prompt-canary");
        raw.put("reply", "raw-reply-canary");
        raw.put("messages", List.of("nested-message-canary"));
        raw.put("reason", "COMPLAINT");
        raw.put("arguments", nested);
        raw.put("codes", new String[]{"SAFE", "password=canary-password"});

        Map<String, Object> sanitized = TraceDataSanitizer.sanitizeData(raw);

        assertEquals(TraceDataSanitizer.OMITTED, sanitized.get("message"));
        assertEquals(TraceDataSanitizer.OMITTED, sanitized.get("promptPreview"));
        assertEquals(TraceDataSanitizer.OMITTED, sanitized.get("reply"));
        assertEquals(TraceDataSanitizer.OMITTED, sanitized.get("messages"));
        assertEquals("COMPLAINT", sanitized.get("reason"));

        Map<?, ?> safeArguments = (Map<?, ?>) sanitized.get("arguments");
        assertEquals("ORD-001", safeArguments.get("orderId"));
        assertEquals("蓝牙耳机", safeArguments.get("keyword"));
        assertEquals(TraceDataSanitizer.REDACTED, safeArguments.get("apiKey"));
        assertEquals(TraceDataSanitizer.REDACTED, safeArguments.get("userToken"));
        assertEquals(TraceDataSanitizer.REDACTED, safeArguments.get("contactEmail"));
        assertFalse(String.valueOf(sanitized).contains("13800138000"));
        assertFalse(String.valueOf(sanitized).contains("buyer@example.com"));
        assertFalse(String.valueOf(sanitized).contains("canary-token"));
        assertFalse(String.valueOf(sanitized).contains("canary-password"));
        assertTrue(sanitized.get("codes") instanceof List<?>);
    }

    @Test
    void shouldOmitReviewNotesWithoutHidingStructuredDecisionFields() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("decision_note", "人工确认远端工单已经创建");
        nested.put("reasonNote", "客户提供了包含隐私的补充说明");
        nested.put("reason_code", "REMOTE_CONFIRMED");
        nested.put("action", "CONFIRM");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("decisionNote", "管理员核对手机号后作出决定");
        raw.put("reason_note", "备注中可能包含任意用户原文");
        raw.put("reasonCode", "MANUAL_REVIEW");
        raw.put("action", "RETRY");
        raw.put("review", nested);

        Map<String, Object> sanitized = TraceDataSanitizer.sanitizeData(raw);

        // decisionNote/reasonNote 是管理员输入的自由文本，无论 camelCase 还是 snake_case，
        // 都只在 Trace 中留下省略标记；reasonCode/action 则是安全且有排障价值的结构化字段。
        assertEquals(TraceDataSanitizer.OMITTED, sanitized.get("decisionNote"));
        assertEquals(TraceDataSanitizer.OMITTED, sanitized.get("reason_note"));
        assertEquals("MANUAL_REVIEW", sanitized.get("reasonCode"));
        assertEquals("RETRY", sanitized.get("action"));

        Map<?, ?> safeReview = (Map<?, ?>) sanitized.get("review");
        assertEquals(TraceDataSanitizer.OMITTED, safeReview.get("decision_note"));
        assertEquals(TraceDataSanitizer.OMITTED, safeReview.get("reasonNote"));
        assertEquals("REMOTE_CONFIRMED", safeReview.get("reason_code"));
        assertEquals("CONFIRM", safeReview.get("action"));
    }

    @Test
    void shouldCreateImmutableDeepCopyInsteadOfKeepingCallerObjects() {
        List<Object> mutableItems = new ArrayList<>(List.of("first"));
        String[] mutableCodes = {"ARRAY-001"};
        Map<String, Object> mutableNested = new LinkedHashMap<>();
        mutableNested.put("items", mutableItems);
        mutableNested.put("codes", mutableCodes);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("nested", mutableNested);

        Map<String, Object> sanitized = TraceDataSanitizer.sanitizeData(raw);
        Map<?, ?> safeNested = (Map<?, ?>) sanitized.get("nested");
        List<?> safeItems = (List<?>) safeNested.get("items");
        List<?> safeCodes = (List<?>) safeNested.get("codes");

        mutableItems.add("changed-after-trace");
        mutableCodes[0] = "CHANGED-AFTER-TRACE";
        mutableNested.put("later", true);
        raw.put("another", "value");

        assertEquals(List.of("first"), safeItems);
        assertEquals(List.of("ARRAY-001"), safeCodes);
        assertFalse(safeNested.containsKey("later"));
        assertFalse(sanitized.containsKey("another"));
        assertThrows(UnsupportedOperationException.class,
                () -> sanitized.put("forbidden", "value"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) safeItems).add("forbidden"));
    }

    @Test
    void shouldStopCircularReferencesAndExcessiveNesting() {
        Map<String, Object> circular = new LinkedHashMap<>();
        circular.put("self", circular);

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < TraceDataSanitizer.MAX_NESTING_DEPTH + 2; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("level" + i, next);
            cursor = next;
        }

        Map<String, Object> sanitized = TraceDataSanitizer.sanitizeData(Map.of(
                "circular", circular,
                "deep", deep
        ));

        assertTrue(String.valueOf(sanitized).contains(TraceDataSanitizer.CIRCULAR_REFERENCE));
        assertTrue(String.valueOf(sanitized).contains(TraceDataSanitizer.MAX_DEPTH_REACHED));
    }

    @Test
    void shouldLimitStringsMapsAndCollectionsWithoutBreakingUnicode() {
        Map<String, Object> largeMap = new LinkedHashMap<>();
        List<Integer> largeList = new ArrayList<>();
        for (int i = 0; i < TraceDataSanitizer.MAX_MAP_ENTRIES + 20; i++) {
            largeMap.put("key" + i, i);
            largeList.add(i);
        }

        String longText = "😀".repeat(TraceDataSanitizer.MAX_TEXT_CODE_POINTS + 20);
        Map<String, Object> sanitized = TraceDataSanitizer.sanitizeData(Map.of(
                "longValue", longText,
                "largeMap", largeMap,
                "largeList", largeList
        ));

        String safeText = (String) sanitized.get("longValue");
        assertTrue(safeText.codePointCount(0, safeText.length())
                <= TraceDataSanitizer.MAX_TEXT_CODE_POINTS);
        assertTrue(((Map<?, ?>) sanitized.get("largeMap")).size()
                <= TraceDataSanitizer.MAX_MAP_ENTRIES);
        assertTrue(((List<?>) sanitized.get("largeList")).size()
                <= TraceDataSanitizer.MAX_COLLECTION_ITEMS);
    }

    @Test
    void shouldRedactBeforeHandlerPreviewTruncatesText() {
        assertEquals("[REDACTED]", HandlerTraceSupport.preview("buyer@example.com", 10));
        assertThrows(IllegalArgumentException.class,
                () -> HandlerTraceSupport.preview("text", 0));
    }

    @Test
    void traceEventShouldExposeOnlySanitizedSnapshotInGettersAndToString() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("answer", "answer-canary 13800138000");
        raw.put("orderId", "ORD-001");

        TraceEvent event = new TraceEvent(
                "final_answer",
                2,
                "完成处理，token=message-canary",
                raw,
                123L
        );
        raw.put("orderId", "CHANGED");

        assertFalse(event.getMessage().contains("message-canary"));
        assertEquals(TraceDataSanitizer.OMITTED, event.getData().get("answer"));
        assertEquals("ORD-001", event.getData().get("orderId"));
        assertFalse(event.toString().contains("answer-canary"));
        assertFalse(event.toString().contains("13800138000"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.getData().put("forbidden", "value"));
    }
}
