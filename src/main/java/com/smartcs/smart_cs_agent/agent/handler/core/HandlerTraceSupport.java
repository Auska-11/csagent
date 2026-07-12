package com.smartcs.smart_cs_agent.agent.handler.core;

import com.smartcs.smart_cs_agent.observability.trace.TraceDataSanitizer;

/** Handler 共用的无状态 Trace 小工具；文本预览统一先脱敏、再截断。 */
public final class HandlerTraceSupport {

    private HandlerTraceSupport() {
    }

    public static String preview(Object value, int maxLength) {
        return TraceDataSanitizer.safePreview(value, maxLength);
    }

    public static long elapsedMillis(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        return Math.max(0, elapsedNanos / 1_000_000);
    }
}
