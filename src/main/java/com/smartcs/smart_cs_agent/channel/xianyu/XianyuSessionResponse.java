package com.smartcs.smart_cs_agent.channel.xianyu;

/**
 * 侧车会话注册响应。
 *
 * @param userId         闲鱼买家幂等映射出的合成用户（USER-XY-{hash}）
 * @param token          买家 JWT，供侧车以普通 CUSTOMER 身份调用 /api/chat/send
 * @param conversationId Java 侧统一生成的会话键（xianyu_{buyerId}_{itemId}），侧车不得自行拼接
 * @param expiresAt      买家 JWT 过期时间（epoch 毫秒），过期后侧车凭 401 自动重注册
 * @param itemKnowledge  结构化商品审核草稿处理结果（字段名保留兼容侧车）：created / existing / skipped / none
 */
public record XianyuSessionResponse(
        String userId,
        String token,
        String conversationId,
        long expiresAt,
        String tenantId,
        String itemKnowledge
) {
}
