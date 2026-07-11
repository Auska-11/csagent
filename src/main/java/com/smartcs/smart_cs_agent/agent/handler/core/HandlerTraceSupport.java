package com.smartcs.smart_cs_agent.agent.handler.core;

/** Handler 共用的无状态 Trace 小工具。 */
public final class HandlerTraceSupport {

    private HandlerTraceSupport() {
    }

    public static String preview(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    public static long elapsedMillis(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        return Math.max(0, elapsedNanos / 1_000_000);
    }
}
