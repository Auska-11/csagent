package com.smartcs.smart_cs_agent.channel.xianyu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * 闲鱼侧车身份推导：buyerId/itemId 到本地会话身份的幂等映射。
 *
 * <p>会话注册（X2）与后续卖家侧端点（人工模式、卖家消息、商品事实）都必须经过同一套
 * 推导，保证“同一买家同一商品”永远落在同一个 ConversationKey 上；任何一端自行拼接
 * 都会出现两个会话。</p>
 */
public final class XianyuIdentity {

    /** 闲鱼会话 ID 的固定前缀（xianyu_{buyerId}_{itemId}）。 */
    public static final String CONVERSATION_PREFIX = "xianyu_";

    private XianyuIdentity() {
    }

    public static String conversationId(String buyerId, String itemId) {
        return CONVERSATION_PREFIX + buyerId + "_" + itemId;
    }

    public static boolean isXianyuConversationId(String conversationId) {
        return conversationId != null && conversationId.startsWith(CONVERSATION_PREFIX);
    }

    /**
     * 从会话 ID 反解 (buyerId, itemId)。闲鱼平台 ID 为纯数字；两个 ID 都完整、非空且
     * 不含分隔符时才视为合法——例如 "xianyu_12345_67890"。非闲鱼或畸形的会话 ID 返回空，
     * 调用方（SLA v1 建单）据此 fail-closed，而不是猜测坐标。
     */
    public static Optional<XianyuCoordinates> parseConversationId(String conversationId) {
        if (!isXianyuConversationId(conversationId)) {
            return Optional.empty();
        }
        String body = conversationId.substring(CONVERSATION_PREFIX.length());
        int separator = body.indexOf('_');
        if (separator <= 0 || separator == body.length() - 1) {
            return Optional.empty();
        }
        String buyerId = body.substring(0, separator);
        String itemId = body.substring(separator + 1);
        if (buyerId.isEmpty() || itemId.isEmpty()
                || buyerId.indexOf('_') >= 0
                || !buyerId.chars().allMatch(Character::isDigit)
                || !itemId.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(new XianyuCoordinates(buyerId, itemId));
    }

    /** 会话 ID 中编码的闲鱼平台坐标：买家 ID 与商品 ID。 */
    public record XianyuCoordinates(String buyerId, String itemId) {
    }

    /**
     * 买家到合成用户的幂等映射：同一 buyerId 永远得到同一 userId，但 userId 本身不携带
     * 平台 ID 明文。8 个十六进制字符（32 位）对合成本地用户已足够，碰撞只意味着两个
     * 买家共享会话记忆，不产生越权（身份仍由 JWT 决定）。
     */
    public static String syntheticUserId(String buyerId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("xianyu:" + buyerId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "USER-XY-" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
