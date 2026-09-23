package com.smartcs.smart_cs_agent.handoff.sla;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 内部 pending handoff 幂等键到 SLA v1 requestId 的稳定映射。
 *
 * <p>契约要求：requestId 为 1–128 个 ASCII 字母/数字/点/下划线/短横线；内部键最长 255，
 * 禁止截断，也禁止每次重试生成新值。对 (tenantId, idempotencyKey) 全量做 SHA-256 再加
 * 前缀，得到 70 字符的确定性 ID——同一租户同一操作永远映射到同一事项，跨租户同键不碰撞。</p>
 */
public final class SlaV1RequestIds {

    public static final String PREFIX = "wi-v1-";

    /** 契约允许的字符集：ASCII 字母、数字、点、下划线、短横线，1–128 位。 */
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private SlaV1RequestIds() {
    }

    public static String derive(String tenantId, String idempotencyKey) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be blank");
        }
        String canonical = tenantId.trim() + "\n" + idempotencyKey;
        String requestId;
        try {
            requestId = PREFIX + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        if (!VALID_REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalStateException("Derived SLA requestId is not contract-compliant");
        }
        return requestId;
    }

    /** 校验外部（回调载荷）携带的 requestId 是否符合契约格式。 */
    public static boolean isValid(String requestId) {
        return requestId != null && VALID_REQUEST_ID.matcher(requestId).matches();
    }
}
