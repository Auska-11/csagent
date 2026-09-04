package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.controller.ApiNotFoundException;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 闲鱼侧车会话注册端点（第三阶段 X2 契约）。
 *
 * <p>认证模型与 JWT 端点不同：调用方是持有静态服务令牌的本机侧车进程，不是终端用户，
 * 因此这里不走 Spring Security 的 Bearer JWT 过滤链，而是在 Controller 内做恒定时间
 * 令牌比对（失败统一 401，不区分“缺失/错误”）。未启用时按 404 处理，避免暴露接入面。</p>
 */
@RestController
@RequestMapping("/api/xianyu")
public class XianyuSidecarController {

    /** 闲鱼买家/商品 ID 都是平台数字 ID；限制字符集防止它们进入文件名或会话键。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Logger LOGGER = LoggerFactory.getLogger(XianyuSidecarController.class);

    private final XianyuSidecarProperties properties;
    private final XianyuBuyerTokenService tokenService;
    private final XianyuItemKnowledgeWriter itemKnowledgeWriter;

    public XianyuSidecarController(XianyuSidecarProperties properties,
                                   XianyuBuyerTokenService tokenService,
                                   XianyuItemKnowledgeWriter itemKnowledgeWriter) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.itemKnowledgeWriter = itemKnowledgeWriter;
    }

    @PostMapping("/sessions")
    public ResponseEntity<XianyuSessionResponse> registerSession(
            @RequestBody(required = false) XianyuSessionRequest request,
            HttpServletRequest http) {
        if (!properties.isEnabled()) {
            // 与 DemoAuthController 同一原则：未启用的端点不存在，而不是 403。
            throw new ApiNotFoundException("Xianyu sidecar endpoint not found");
        }
        requireServiceToken(http.getHeader("Authorization"));
        if (request == null) {
            throw new IllegalArgumentException("buyerId and itemId are required");
        }
        String buyerId = requireSafeId(request.buyerId(), "buyerId");
        String itemId = requireSafeId(request.itemId(), "itemId");

        String userId = syntheticUserId(buyerId);
        XianyuBuyerTokenService.IssuedBuyerToken issued = tokenService.issue(userId);
        String conversationId = "xianyu_" + buyerId + "_" + itemId;
        String itemKnowledge = request.itemSnapshot() == null || request.itemSnapshot().isBlank()
                ? "none"
                : itemKnowledgeWriter.writeDraft(
                        itemId,
                        request.itemSnapshot(),
                        Path.of(properties.getItemSnapshotDir())
                );
        LOGGER.info(
                "闲鱼侧车会话注册: buyer={} item={} -> {} / {} (itemKnowledge={})",
                buyerId, itemId, userId, conversationId, itemKnowledge
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new XianyuSessionResponse(
                        userId,
                        issued.token(),
                        conversationId,
                        issued.expiresAtEpochMillis(),
                        properties.getTenantId(),
                        itemKnowledge
                ));
    }

    private void requireServiceToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw AuthenticationException.required();
        }
        String presented = authorizationHeader.substring("Bearer ".length());
        if (!MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.getServiceToken().getBytes(StandardCharsets.UTF_8))) {
            LOGGER.warn("闲鱼侧车服务令牌校验失败");
            throw AuthenticationException.failed();
        }
    }

    private String requireSafeId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match [A-Za-z0-9_-]{1,64}");
        }
        return value;
    }

    /**
     * 买家到合成用户的幂等映射：同一 buyerId 永远得到同一 userId，但 userId 本身不携带
     * 平台 ID 明文。8 个十六进制字符（32 位）对合成本地用户已足够，碰撞只意味着两个
     * 买家共享会话记忆，不产生越权（身份仍由 JWT 决定）。
     */
    private String syntheticUserId(String buyerId) {
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
