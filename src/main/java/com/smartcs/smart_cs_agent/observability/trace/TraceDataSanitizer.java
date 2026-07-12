package com.smartcs.smart_cs_agent.observability.trace;

import com.smartcs.smart_cs_agent.security.SensitiveTextRedactor;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trace 数据的统一隐私与容量边界。
 *
 * <p>业务代码仍然使用原始参数调用 Workflow、Provider 和 LLM；只有写入 Trace 的副本会经过这里。
 * 这样既不会改变真实业务行为，也能避免调试接口、日志或监控页面意外暴露用户输入和凭据。</p>
 */
public final class TraceDataSanitizer {

    static final int MAX_TEXT_CODE_POINTS = 512;
    static final int MAX_MESSAGE_CODE_POINTS = 256;
    static final int MAX_KEY_CODE_POINTS = 80;
    static final int MAX_MAP_ENTRIES = 50;
    static final int MAX_COLLECTION_ITEMS = 50;
    static final int MAX_NESTING_DEPTH = 8;

    public static final String REDACTED = "[REDACTED]";
    public static final String OMITTED = "[OMITTED]";
    public static final String CIRCULAR_REFERENCE = "[CIRCULAR_REFERENCE]";
    public static final String MAX_DEPTH_REACHED = "[MAX_DEPTH_REACHED]";

    private static final String TRUNCATED_ENTRIES_KEY = "__trace_truncated_entries__";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "secret", "apikey", "token",
            "accesstoken", "refreshtoken", "authtoken", "authorization",
            "bearertoken", "idtoken", "sessiontoken", "authheader", "cookie",
            "setcookie", "credential", "credentials", "privatekey", "clientsecret",
            "email", "emailaddress", "phone", "phonenumber", "mobile",
            "mobilenumber", "idcard", "identitycard", "identitynumber",
            "bankcard", "creditcard", "cvv"
    );

    private static final Set<String> FREE_TEXT_KEYS = Set.of(
            "message", "prompt", "query", "summary", "answer", "response",
            "result", "content", "text", "error", "internalmessage",
            "errormessage", "exceptionmessage", "stacktrace", "messages",
            "prompts", "queries", "summaries", "answers", "responses",
            "results", "contents", "reply", "replies"
    );

    private TraceDataSanitizer() {
    }

    /**
     * 清洗一条 Trace 的结构化数据，并返回不可变深拷贝。
     *
     * <p>IdentityHashMap 按对象身份记录当前递归路径，可以识别 {@code map.put("self", map)}
     * 这类循环引用，避免 Trace 清洗本身发生栈溢出。</p>
     */
    public static Map<String, Object> sanitizeData(Map<String, ?> data) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyMap();
        }
        return sanitizeMap(data, 0, new IdentityHashMap<>());
    }

    /** Trace 的展示说明也要脱敏，但它不是用户原始 message 字段，因此可以保留安全预览。 */
    public static String sanitizeMessage(String message) {
        return safePreview(message, MAX_MESSAGE_CODE_POINTS);
    }

    /**
     * 生成“先脱敏、后截断”的单行预览。
     *
     * <p>顺序非常重要：如果先把邮箱截成 {@code buyer@exam}，邮箱正则就再也识别不到它了。</p>
     */
    public static String safePreview(Object value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints must be greater than 0");
        }
        return sanitizeText(String.valueOf(value), maxCodePoints);
    }

    private static Map<String, Object> sanitizeMap(
            Map<?, ?> source,
            int depth,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (activeContainers.put(source, Boolean.TRUE) != null) {
            return Map.of("value", CIRCULAR_REFERENCE);
        }

        try {
            Map<String, Object> target = new LinkedHashMap<>();
            boolean needsTruncationMarker = source.size() > MAX_MAP_ENTRIES;
            int valueLimit = needsTruncationMarker ? MAX_MAP_ENTRIES - 1 : MAX_MAP_ENTRIES;
            int copied = 0;

            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (copied >= valueLimit) {
                    break;
                }
                String rawKey = String.valueOf(entry.getKey());
                String safeKey = sanitizeText(rawKey, MAX_KEY_CODE_POINTS);
                target.put(safeKey, sanitizeValue(
                        entry.getValue(),
                        rawKey,
                        depth + 1,
                        activeContainers
                ));
                copied++;
            }

            if (needsTruncationMarker) {
                target.put(TRUNCATED_ENTRIES_KEY, Math.max(0, source.size() - copied));
            }
            return Collections.unmodifiableMap(target);
        } finally {
            activeContainers.remove(source);
        }
    }

    private static Object sanitizeValue(
            Object value,
            String fieldName,
            int depth,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (isSensitiveKey(fieldName)) {
            return REDACTED;
        }
        if (value == null) {
            return null;
        }

        // message、prompt、summary 等字段本质上可能包含任意用户原文。即使没有固定格式，
        // 也不应该依赖正则“碰巧识别到”，所以在 Trace 中统一只留下省略标记。
        if (isFreeTextKey(fieldName)) {
            return OMITTED;
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return sanitizeText(String.valueOf(value), MAX_TEXT_CODE_POINTS);
        }

        // Number 和 Boolean 是不可变值，并且耗时、计数、成功状态等字段对排障很有价值。
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof UUID || value instanceof TemporalAccessor) {
            return sanitizeText(String.valueOf(value), MAX_TEXT_CODE_POINTS);
        }

        if (value instanceof Map<?, ?> map) {
            if (depth >= MAX_NESTING_DEPTH) {
                return MAX_DEPTH_REACHED;
            }
            if (activeContainers.containsKey(map)) {
                return CIRCULAR_REFERENCE;
            }
            return sanitizeMap(map, depth, activeContainers);
        }
        if (value instanceof Collection<?> collection) {
            if (depth >= MAX_NESTING_DEPTH) {
                return MAX_DEPTH_REACHED;
            }
            return sanitizeCollection(collection, depth, activeContainers);
        }
        if (value.getClass().isArray()) {
            if (depth >= MAX_NESTING_DEPTH) {
                return MAX_DEPTH_REACHED;
            }
            return sanitizeArray(value, depth, activeContainers);
        }

        // 未知可变对象不能原样挂到 Trace 上，否则调用方稍后修改对象仍会改写历史。
        // 同时也不要调用未知对象的 toString()，它可能把整个领域对象和隐私字段都打印出来。
        return "[OBJECT:" + value.getClass().getSimpleName() + "]";
    }

    private static List<Object> sanitizeCollection(
            Collection<?> source,
            int depth,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (activeContainers.put(source, Boolean.TRUE) != null) {
            return List.of(CIRCULAR_REFERENCE);
        }

        try {
            List<Object> target = new ArrayList<>();
            boolean needsTruncationMarker = source.size() > MAX_COLLECTION_ITEMS;
            int valueLimit = needsTruncationMarker ? MAX_COLLECTION_ITEMS - 1 : MAX_COLLECTION_ITEMS;
            int copied = 0;
            for (Object item : source) {
                if (copied >= valueLimit) {
                    break;
                }
                target.add(sanitizeValue(item, null, depth + 1, activeContainers));
                copied++;
            }
            if (needsTruncationMarker) {
                target.add("[TRUNCATED:" + Math.max(0, source.size() - copied) + "]");
            }
            return Collections.unmodifiableList(target);
        } finally {
            activeContainers.remove(source);
        }
    }

    private static List<Object> sanitizeArray(
            Object source,
            int depth,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (activeContainers.put(source, Boolean.TRUE) != null) {
            return List.of(CIRCULAR_REFERENCE);
        }

        try {
            int length = Array.getLength(source);
            List<Object> target = new ArrayList<>();
            boolean needsTruncationMarker = length > MAX_COLLECTION_ITEMS;
            int valueLimit = needsTruncationMarker ? MAX_COLLECTION_ITEMS - 1 : MAX_COLLECTION_ITEMS;
            int copied = Math.min(length, valueLimit);
            for (int i = 0; i < copied; i++) {
                target.add(sanitizeValue(Array.get(source, i), null, depth + 1, activeContainers));
            }
            if (needsTruncationMarker) {
                target.add("[TRUNCATED:" + (length - copied) + "]");
            }
            return Collections.unmodifiableList(target);
        } finally {
            activeContainers.remove(source);
        }
    }

    private static String sanitizeText(String text, int maxCodePoints) {
        String normalized = SensitiveTextRedactor.normalizeSingleLine(text);
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount <= maxCodePoints) {
            return normalized;
        }
        if (maxCodePoints <= 3) {
            return SensitiveTextRedactor.limitSingleLine(normalized, maxCodePoints);
        }

        String head = SensitiveTextRedactor.limitSingleLine(normalized, maxCodePoints - 3);
        return head + "...";
    }

    private static boolean isSensitiveKey(String fieldName) {
        String normalized = normalizeKey(fieldName);
        if (normalized.isEmpty()) {
            return false;
        }
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        if (containsAny(normalized,
                "password", "passwd", "secret", "apikey", "accesstoken",
                "refreshtoken", "authtoken", "bearertoken", "idtoken",
                "sessiontoken", "authorization", "authheader", "cookie",
                "credential", "privatekey", "clientsecret")) {
            return true;
        }
        if (endsWithAny(normalized,
                "token", "email", "emailaddress", "phone",
                "phonenumber", "mobile", "mobilenumber", "idcard",
                "identitycard", "identitynumber", "bankcard", "creditcard", "cvv")) {
            return true;
        }
        return normalized.contains("密码")
                || normalized.contains("口令")
                || normalized.contains("密钥")
                || normalized.contains("令牌")
                || normalized.contains("手机")
                || normalized.contains("电话")
                || normalized.contains("邮箱")
                || normalized.contains("身份证")
                || normalized.contains("银行卡");
    }

    private static boolean isFreeTextKey(String fieldName) {
        String normalized = normalizeKey(fieldName);
        return FREE_TEXT_KEYS.contains(normalized)
                || endsWithAny(normalized,
                "message", "prompt", "query", "summary", "answer", "response",
                "content", "reply", "preview", "stacktrace");
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String fieldName) {
        if (fieldName == null) {
            return "";
        }
        return fieldName.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
