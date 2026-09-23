package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.controller.ApiNotFoundException;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 闲鱼侧车端点（第三阶段 X2 契约 + 卖家侧协作端点）。
 *
 * <p>认证模型与 JWT 端点不同：调用方是持有静态服务令牌的本机侧车进程，不是终端用户，
 * 因此这里不走 Spring Security 的 Bearer JWT 过滤链，而是在 Controller 内做恒定时间
 * 令牌比对（失败统一 401，不区分“缺失/错误”）。未启用时按 404 处理，避免暴露接入面。</p>
 *
 * <p>卖家侧端点服务于“暂停机器人让卖家接管”闭环：</p>
 * <ul>
 *     <li>{@code POST /items/{itemId}/facts}：改价/售出热更新，进入商品事实覆盖层；</li>
 *     <li>{@code GET/POST /conversations/manual-mode}：查询/切换人工模式（暂停或恢复机器人）；</li>
 *     <li>{@code POST /conversations/seller-messages}：卖家以人工身份发消息，可选同时恢复机器人。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/xianyu")
public class XianyuSidecarController {

    /** 闲鱼买家/商品 ID 都是平台数字 ID；限制字符集防止它们进入文件名或会话键。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 快照里的库存可能是数字，也可能是“5 件”这类文本，取首个数字串。 */
    private static final Pattern STOCK_DIGITS = Pattern.compile("(\\d+)");

    private static final Logger LOGGER = LoggerFactory.getLogger(XianyuSidecarController.class);

    private final XianyuSidecarProperties properties;
    private final XianyuBuyerTokenService tokenService;
    private final XianyuItemKnowledgeWriter itemKnowledgeWriter;
    private final XianyuItemFactService itemFactService;
    private final XianyuManualModeService manualModeService;
    private final ObjectMapper objectMapper;

    public XianyuSidecarController(XianyuSidecarProperties properties,
                                   XianyuBuyerTokenService tokenService,
                                   XianyuItemKnowledgeWriter itemKnowledgeWriter,
                                   XianyuItemFactService itemFactService,
                                   XianyuManualModeService manualModeService,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.itemKnowledgeWriter = itemKnowledgeWriter;
        this.itemFactService = itemFactService;
        this.manualModeService = manualModeService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /** 商品事实热更新请求：price 为裸价格文本（“265”/“265-280”/“¥265”），两字段均可空表示不变。 */
    public record XianyuItemFactsUpdateRequest(String price, Integer stock) {
    }

    /** 商品事实当前覆盖状态（从未推送过覆盖时 price/stock 为 null）。 */
    public record XianyuItemFactsResponse(String itemId, String price, Integer stock) {
    }

    /** 人工模式切换请求；enabled=true 暂停机器人，false 恢复。 */
    public record XianyuManualModeRequest(String buyerId, String itemId, Boolean enabled) {
    }

    /** 人工模式状态。 */
    public record XianyuManualModeResponse(String conversationId, boolean manualMode) {
    }

    /** 卖家人工消息请求；resumeBot=true 时随消息一起恢复机器人自动回复。 */
    public record XianyuSellerMessageRequest(String buyerId, String itemId, String text, Boolean resumeBot) {
    }

    /** 卖家消息写入结果。 */
    public record XianyuSellerMessageResponse(String conversationId, String deliveredText, boolean botResumed) {
    }

    @PostMapping("/sessions")
    public ResponseEntity<XianyuSessionResponse> registerSession(
            @RequestBody(required = false) XianyuSessionRequest request,
            HttpServletRequest http) {
        requireEnabled();
        requireServiceToken(http.getHeader("Authorization"));
        if (request == null) {
            throw new IllegalArgumentException("buyerId and itemId are required");
        }
        String buyerId = requireSafeId(request.buyerId(), "buyerId");
        String itemId = requireSafeId(request.itemId(), "itemId");

        String userId = XianyuIdentity.syntheticUserId(buyerId);
        XianyuBuyerTokenService.IssuedBuyerToken issued = tokenService.issue(userId);
        String conversationId = XianyuIdentity.conversationId(buyerId, itemId);
        String itemKnowledge = request.itemSnapshot() == null || request.itemSnapshot().isBlank()
                ? "none"
                : itemKnowledgeWriter.writeDraft(
                        itemId,
                        request.itemSnapshot(),
                        Path.of(properties.getItemSnapshotDir())
                );
        // 注册即建立现势事实：知识文档要走人工审校（慢），改价/售出对买家必须即时生效。
        applySnapshotOverride(itemId, request.itemSnapshot());
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

    /**
     * 商品事实热更新（改价/售出）。只更新覆盖层，不改知识文档；价格文本不合法返回 400
     * 而不是猜一个值。两字段都缺失同样 400——空推送没有语义。
     */
    @PostMapping("/items/{itemId}/facts")
    public ResponseEntity<XianyuItemFactsResponse> updateItemFacts(
            @PathVariable("itemId") String itemId,
            @RequestBody(required = false) XianyuItemFactsUpdateRequest request,
            HttpServletRequest http) {
        requireEnabled();
        requireServiceToken(http.getHeader("Authorization"));
        String safeItemId = requireSafeId(itemId, "itemId");
        if (request == null || (isBlank(request.price()) && request.stock() == null)) {
            throw new IllegalArgumentException("price or stock is required");
        }
        Optional<XianyuItemFactService.PriceRange> price = Optional.empty();
        if (!isBlank(request.price())) {
            price = XianyuItemFactService.parsePriceRangeText(request.price());
            if (price.isEmpty()) {
                throw new IllegalArgumentException("price must look like \"265\" or \"265-280\"");
            }
        }
        Integer stock = request.stock();
        if (stock != null && stock < 0) {
            throw new IllegalArgumentException("stock must be >= 0");
        }
        itemFactService.applyLiveOverride(safeItemId, price.orElse(null), stock);
        LOGGER.info("闲鱼商品事实热更新: item={} price={} stock={}", safeItemId, request.price(), stock);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentFactsResponse(safeItemId));
    }

    /** 查询某商品当前的覆盖事实（调试/对账用，也可供侧车注册后校验）。 */
    @GetMapping("/items/{itemId}/facts")
    public ResponseEntity<XianyuItemFactsResponse> getItemFacts(
            @PathVariable("itemId") String itemId,
            HttpServletRequest http) {
        requireEnabled();
        requireServiceToken(http.getHeader("Authorization"));
        String safeItemId = requireSafeId(itemId, "itemId");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentFactsResponse(safeItemId));
    }

    /** 切换人工模式（暂停/恢复机器人自动回复）。会话不存在返回 404。 */
    @PostMapping("/conversations/manual-mode")
    public ResponseEntity<XianyuManualModeResponse> setManualMode(
            @RequestBody(required = false) XianyuManualModeRequest request,
            HttpServletRequest http) {
        requireEnabled();
        requireServiceToken(http.getHeader("Authorization"));
        String buyerId = requireBuyerAndItem(request == null ? null : request.buyerId(),
                request == null ? null : request.itemId());
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        if (!manualModeService.setManualMode(buyerId, request.itemId(), request.enabled())) {
            throw new ApiNotFoundException("Xianyu conversation not found");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new XianyuManualModeResponse(
                        XianyuIdentity.conversationId(buyerId, request.itemId()),
                        request.enabled()
                ));
    }

    /** 查询人工模式状态。会话不存在返回 404。 */
    @GetMapping("/conversations/manual-mode")
    public ResponseEntity<XianyuManualModeResponse> getManualMode(
            @RequestParam("buyerId") String buyerId,
            @RequestParam("itemId") String itemId,
            HttpServletRequest http) {
        requireEnabled();
        requireServiceToken(http.getHeader("Authorization"));
        String safeBuyerId = requireBuyerAndItem(buyerId, itemId);
        Optional<Boolean> current = manualModeService.manualMode(safeBuyerId, itemId);
        if (current.isEmpty()) {
            throw new ApiNotFoundException("Xianyu conversation not found");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new XianyuManualModeResponse(
                        XianyuIdentity.conversationId(safeBuyerId, itemId),
                        current.get()
                ));
    }

    /**
     * 卖家以人工身份发消息：原文写入会话（买家看到卖家口径），resumeBot=true 时同时
     * 恢复机器人。会话不存在返回 404。
     */
    @PostMapping("/conversations/seller-messages")
    public ResponseEntity<XianyuSellerMessageResponse> sendSellerMessage(
            @RequestBody(required = false) XianyuSellerMessageRequest request,
            HttpServletRequest http) {
        requireEnabled();
        requireServiceToken(http.getHeader("Authorization"));
        String buyerId = requireBuyerAndItem(request == null ? null : request.buyerId(),
                request == null ? null : request.itemId());
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (request.text().length() > 2000) {
            throw new IllegalArgumentException("text too long (max 2000 chars)");
        }
        boolean resumeBot = Boolean.TRUE.equals(request.resumeBot());
        if (!manualModeService.addSellerMessage(buyerId, request.itemId(), request.text().strip(), resumeBot)) {
            throw new ApiNotFoundException("Xianyu conversation not found");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new XianyuSellerMessageResponse(
                        XianyuIdentity.conversationId(buyerId, request.itemId()),
                        request.text().strip(),
                        resumeBot
                ));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            // 与 DemoAuthController 同一原则：未启用的端点不存在，而不是 403。
            throw new ApiNotFoundException("Xianyu sidecar endpoint not found");
        }
    }

    /**
     * 注册快照 → 覆盖层 seed：解析 price_range/total_stock，解析不了就跳过（文档兜底）。
     * 该商品已有覆盖（运行期热更新、或重启后从磁盘恢复的）时快照是旧值，不覆盖新状态。
     */
    private void applySnapshotOverride(String itemId, String itemSnapshot) {
        if (itemSnapshot == null || itemSnapshot.isBlank()) {
            return;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(itemSnapshot);
            Optional<XianyuItemFactService.PriceRange> price =
                    XianyuItemFactService.parsePriceRangeText(snapshot.path("price_range").asText());
            Integer stock = parseStock(snapshot.path("total_stock"));
            if (price.isPresent() || stock != null) {
                boolean seeded = itemFactService.seedLiveOverride(itemId, price.orElse(null), stock);
                if (!seeded) {
                    LOGGER.info("闲鱼商品已有更新的覆盖事实，注册快照不覆盖: item={}", itemId);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("闲鱼商品快照事实解析失败（item={}），跳过覆盖层更新", itemId, exception);
        }
    }

    private Integer parseStock(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.canConvertToInt()) {
            return node.asInt();
        }
        Matcher matcher = STOCK_DIGITS.matcher(node.asText());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private XianyuItemFactsResponse currentFactsResponse(String itemId) {
        return itemFactService.liveOverride(itemId)
                .map(override -> new XianyuItemFactsResponse(
                        itemId,
                        override.price() == null || !override.price().known()
                                ? null
                                : override.price().display(),
                        override.stock()
                ))
                .orElseGet(() -> new XianyuItemFactsResponse(itemId, null, null));
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

    private String requireBuyerAndItem(String buyerId, String itemId) {
        String safeBuyerId = requireSafeId(buyerId, "buyerId");
        requireSafeId(itemId, "itemId");
        return safeBuyerId;
    }

    private String requireSafeId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match [A-Za-z0-9_-]{1,64}");
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
