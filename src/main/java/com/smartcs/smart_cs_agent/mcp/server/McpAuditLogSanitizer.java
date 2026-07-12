package com.smartcs.smart_cs_agent.mcp.server;

import com.smartcs.smart_cs_agent.security.SensitiveTextRedactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.PrimitiveIterator;

/**
 * MCP 普通审计日志的最小化与防注入工具。
 *
 * <p>职责：把外部可控标识变成适合单行日志的有限字符串，并把 requestId 变成不可逆短引用。</p>
 * <p>不负责：不清洗业务 arguments，也不生成用户回复；这些数据从一开始就不应传给日志方法。</p>
 *
 * <p>调用链：McpServerAdapter -> McpAuditLogSanitizer -> SLF4J 参数化日志。</p>
 */
final class McpAuditLogSanitizer {

    /** 96 个 Unicode code point 足够排障，又能限制恶意超长标识放大日志。 */
    static final int MAX_IDENTIFIER_CODE_POINTS = 96;
    private static final int REQUEST_REFERENCE_HEX_LENGTH = 16;

    private McpAuditLogSanitizer() {
    }

    /**
     * 清洗 capability、provider、server 和 errorCode 等短标识。
     *
     * <p>先做通用敏感文本脱敏，再只保留字母、数字和少量协议标点。空白、换行、控制字符及其他
     * 标点都会变成下划线，因此外部输入不能伪造下一行日志。</p>
     */
    static String safeIdentifier(String value, String fallback) {
        String source = SensitiveTextRedactor.normalizeSingleLine(value);
        if (source.isBlank()) {
            source = fallback == null || fallback.isBlank() ? "unknown" : fallback;
        }

        StringBuilder safe = new StringBuilder();
        PrimitiveIterator.OfInt iterator = source.codePoints().iterator();
        int written = 0;
        while (iterator.hasNext() && written < MAX_IDENTIFIER_CODE_POINTS) {
            int codePoint = iterator.nextInt();
            if (Character.isLetterOrDigit(codePoint) || isAllowedPunctuation(codePoint)) {
                safe.appendCodePoint(codePoint);
            } else {
                safe.append('_');
            }
            written++;
        }
        return safe.length() == 0 ? "unknown" : safe.toString();
    }

    /**
     * requestId 可能完全由外部调用方填写，甚至被故意塞入 token 或手机号。
     *
     * <p>普通日志只记录 SHA-256 前 16 位。相同 requestId 仍可关联，但无法从日志还原原文。</p>
     */
    static String requestReference(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(
                    digest.digest(requestId.getBytes(StandardCharsets.UTF_8))
            );
            return hash.substring(0, REQUEST_REFERENCE_HEX_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static boolean isAllowedPunctuation(int codePoint) {
        return codePoint == '.'
                || codePoint == '-'
                || codePoint == '_'
                || codePoint == ':'
                || codePoint == '/';
    }
}
