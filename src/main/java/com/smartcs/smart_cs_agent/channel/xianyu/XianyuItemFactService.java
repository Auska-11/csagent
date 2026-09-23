package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchProfile;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchRequest;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchResponse;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.knowledge.service.KnowledgeSearchService;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 闲鱼“当前商品”事实服务：从会话身份解析 itemId，并从审校入库的商品知识文档
 * （productcategory=xianyu）恢复该商品的结构化事实。
 *
 * <p>这是结构化商品上下文的唯一入口，取代“把商品信息拼进提示词”的做法：</p>
 * <ul>
 *     <li>itemId 来自 conversationId（{@code xianyu_{buyerId}_{itemId}}），由侧车 X2 会话
 *         注册生成、买家 JWT 无法篡改——平台身份与商品归属不由模型决定。</li>
 *     <li>价格解析为 {@link BigDecimal} 区间并保留“未知”状态：知识文档写“以商品页为准”
 *         或解析失败时绝不能折算成 0 元（旧 XianyuKnowledgeProductCatalog 的已知缺陷）。</li>
 *     <li>成色/功能未测试等实况描述与型号能力是两类事实，调用方（Workflow/Prompt 组装）
 *         拿到的是同一份 ItemFacts，不会交叉污染。</li>
 * </ul>
 *
 * <p>检索按 documentId 精确取回整篇商品文档（标题 + itemId 都是文档关键词，
 * 命中后按 documentId 过滤），不做语义排序——当前商品是确定的，不需要“猜”。</p>
 */
@Component
public class XianyuItemFactService {

    static final String PRODUCT_DOC_PREFIX = "product/";
    static final String PRODUCT_DOC_SUFFIX = ".md";

    private static final Pattern CONVERSATION_ITEM_ID =
            Pattern.compile("^xianyu_[A-Za-z0-9_-]{1,64}_([A-Za-z0-9_-]{1,64})$");

    private static final Pattern PRICE_RANGE_PATTERN = Pattern.compile(
            "价格[:：]\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:[-–~到]\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?))?");
    private static final Pattern STOCK_PATTERN = Pattern.compile("库存[:：]\\s*([0-9]+)");
    private static final Pattern ITEM_ID_LINE = Pattern.compile("商品\\s*ID[:：]\\s*([0-9]+)");

    private final KnowledgeSearchService knowledgeSearchService;
    private final XianyuSidecarProperties sidecarProperties;

    /**
     * 运行时商品事实覆盖层（改价/售出等热更新），优先级高于已入库的知识文档。
     *
     * <p>知识索引在启动时一次性构建、没有热加载，商品改价或售出后靠这里对齐现势：
     * 侧车在会话注册和后续事件里推送覆盖值，价格/库存以最新推送为准；标题、描述、
     * 成色仍来自审校入库的文档（这些不随交易实时变化）。</p>
     *
     * <p>覆盖层是交易语义（“已售出”一旦丢失，重启后就会把无货商品当有货报价），
     * 因此每次合并后同步落盘到 {@code item-snapshot-dir/overrides/}，服务重启时
     * 在构造阶段整目录恢复；磁盘读写失败只降级为内存态并记 WARN，绝不阻断交易
     * 主链路。会话注册携带的快照只做 <em>seed</em>：该商品已有覆盖（含重启恢复的）
     * 时注册快照不得覆盖新状态。</p>
     */
    private final ConcurrentHashMap<String, ItemFactOverride> liveOverrides = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(XianyuItemFactService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PERSISTABLE_ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 覆盖层写盘串行化：更新频率是“改价/售出”级别，全局锁没有竞争问题。 */
    private final Object persistLock = new Object();

    public XianyuItemFactService(KnowledgeSearchService knowledgeSearchService,
                                 XianyuSidecarProperties sidecarProperties) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.sidecarProperties = sidecarProperties;
        loadPersistedOverrides();
    }

    /** 热更新的商品事实：价格与库存均可为 null 表示“本次推送不变”。 */
    public record ItemFactOverride(PriceRange price, Integer stock) {
    }

    /**
     * 更新某商品的现势事实（改价/售出）。空 itemId 或“两字段都为 null”的空推送忽略。
     * 合并结果立即落盘，重启后不回退。
     */
    public void applyLiveOverride(String itemId, PriceRange price, Integer stock) {
        if (itemId == null || itemId.isBlank() || (price == null && stock == null)) {
            return;
        }
        String key = itemId.trim();
        synchronized (persistLock) {
            ItemFactOverride merged = liveOverrides.compute(key, (ignored, current) -> {
                PriceRange nextPrice = price != null ? price : current == null ? null : current.price();
                Integer nextStock = stock != null ? stock : current == null ? null : current.stock();
                return new ItemFactOverride(nextPrice, nextStock);
            });
            persistOverride(key, merged);
        }
    }

    /**
     * 会话注册快照 seed：该商品已有覆盖（运行期热更新，或重启后从磁盘恢复的）时
     * <strong>不覆盖</strong>——注册快照是注册时点的观察值，比覆盖层旧。
     *
     * @return 是否真正写入了覆盖层
     */
    public boolean seedLiveOverride(String itemId, PriceRange price, Integer stock) {
        if (itemId == null || itemId.isBlank() || (price == null && stock == null)) {
            return false;
        }
        String key = itemId.trim();
        synchronized (persistLock) {
            if (liveOverrides.containsKey(key)) {
                return false;
            }
            liveOverrides.put(key, new ItemFactOverride(price, stock));
            persistOverride(key, new ItemFactOverride(price, stock));
            return true;
        }
    }

    /** 查询某商品当前的热更新覆盖值；从未推送过返回 empty。 */
    public Optional<ItemFactOverride> liveOverride(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(liveOverrides.get(itemId.trim()));
    }

    /** 从 xianyu 会话 ID 解析当前商品 ID；非闲鱼会话返回 empty。 */
    public Optional<String> currentItemId(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        Matcher matcher = CONVERSATION_ITEM_ID.matcher(conversationId.trim());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** 当前请求是否属于闲鱼租户（会话身份与租户配置同源校验）。 */
    public boolean isXianyuConversation(String tenantId, String conversationId) {
        return tenantId != null
                && sidecarProperties.getTenantId().equalsIgnoreCase(tenantId.trim())
                && currentItemId(conversationId).isPresent();
    }

    /** 侧车推送的裸价格文本（“265”/“265-280”/“¥265”）；不合法返回 empty，绝不猜。 */
    public static Optional<PriceRange> parsePriceRangeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = RAW_PRICE_TEXT.matcher(raw);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            BigDecimal min = new BigDecimal(matcher.group(1));
            BigDecimal max = matcher.group(2) == null ? min : new BigDecimal(matcher.group(2));
            if (min.compareTo(BigDecimal.ZERO) <= 0 || max.compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }
            return Optional.of(max.compareTo(min) < 0 ? new PriceRange(max, min) : new PriceRange(min, max));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static final Pattern RAW_PRICE_TEXT = Pattern.compile(
            "\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:[-–~到]\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?))?\\s*");

    /**
     * 恢复当前商品事实。知识文档未入库（快照草稿还在人工审校）时返回 empty，
     * 调用方应按“商品信息暂未同步”处理，而不是编造。
     */
    public Optional<ItemFacts> itemFacts(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        String trimmedItemId = itemId.trim();
        String documentId = PRODUCT_DOC_PREFIX + trimmedItemId + PRODUCT_DOC_SUFFIX;
        KnowledgeSearchResponse response = knowledgeSearchService.searchWithDiagnostics(
                new KnowledgeSearchRequest(
                        trimmedItemId,
                        10,
                        new KnowledgeSearchProfile(KnowledgeType.POLICY, "xianyu", "", "")
                )
        );
        List<KnowledgeSearchResult> hits = response.results().stream()
                .filter(hit -> documentId.equals(hit.getDocumentId()))
                .toList();
        ItemFactOverride override = liveOverrides.get(trimmedItemId);
        if (hits.isEmpty()) {
            // 文档未入库（草稿还在人工审校）但已有热更新事实：按覆盖值回答价格/库存，
            // 标题/描述/成色保持未知，绝不编造。
            if (override == null) {
                return Optional.empty();
            }
            return Optional.of(new ItemFacts(
                    trimmedItemId,
                    "",
                    override.price() == null ? PriceRange.unknown() : override.price(),
                    override.stock() == null ? -1 : override.stock(),
                    "",
                    ""
            ));
        }
        String title = titleOf(hits);
        String content = String.join("\n", hits.stream()
                .map(KnowledgeSearchResult::getSnippet)
                .toList());
        return Optional.of(new ItemFacts(
                trimmedItemId,
                title,
                override != null && override.price() != null && override.price().known()
                        ? override.price()
                        : parsePrice(content),
                override != null && override.stock() != null ? override.stock() : parseStock(content),
                descriptionOf(hits, title),
                conditionOf(content)
        ));
    }

    /** 便捷入口：直接按会话 ID 取当前商品事实。 */
    public Optional<ItemFacts> currentItemFacts(String conversationId) {
        return currentItemId(conversationId).flatMap(this::itemFacts);
    }

    private String titleOf(List<KnowledgeSearchResult> hits) {
        String chunkTitle = hits.get(0).getTitle();
        int separator = chunkTitle.indexOf(" / ");
        return separator > 0 ? chunkTitle.substring(0, separator) : chunkTitle;
    }

    /**
     * 价格解析为区间；匹配不到数字（如“以商品页为准”）返回 unknown。
     * 区间两端相等时折叠为单值。这里绝不返回 0——未知价格宁可不说。
     */
    private PriceRange parsePrice(String content) {
        Matcher matcher = PRICE_RANGE_PATTERN.matcher(content);
        if (!matcher.find()) {
            return PriceRange.unknown();
        }
        try {
            BigDecimal min = new BigDecimal(matcher.group(1));
            BigDecimal max = matcher.group(2) == null ? min : new BigDecimal(matcher.group(2));
            if (max.compareTo(min) < 0) {
                BigDecimal swap = min;
                min = max;
                max = swap;
            }
            return new PriceRange(min, max);
        } catch (NumberFormatException ignored) {
            return PriceRange.unknown();
        }
    }

    /** 库存解析；文档没有库存数字时返回 -1 表示未知（不能当作“无货”）。 */
    private int parseStock(String content) {
        Matcher matcher = STOCK_PATTERN.matcher(content);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** 描述取“商品描述”小节；没有时用非基本信息块的片段兜底。 */
    private String descriptionOf(List<KnowledgeSearchResult> hits, String title) {
        for (KnowledgeSearchResult hit : hits) {
            if (hit.getTitle().contains("/ 商品描述")) {
                return stripLeadingTitle(hit.getSnippet(), title);
            }
        }
        for (KnowledgeSearchResult hit : hits) {
            if (!hit.getTitle().contains("/ 基本信息")) {
                return stripLeadingTitle(hit.getSnippet(), title);
            }
        }
        return "";
    }

    /**
     * 成色/实况提取：保留含标记词的<strong>整句原文</strong>。
     *
     * <p>型号支持什么功能（如“支持蓝牙连接”）不等于这一件的实际状态，Workflow 生成
     * 回复时必须把两者区分开。早期实现按标记词拼接（“没有划痕”被压成“划痕”），
     * 否定关系丢失后等于凭空捏造缺陷；成色描述宁可保留原句，也不能改写语义。</p>
     */
    private String conditionOf(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        String[] conditionMarkers = {
                "功能未测试", "未测试", "测试正常", "功能正常", "一切正常",
                "使用痕迹", "磨损", "划痕", "瑕疵", "成色", "95新", "99新", "9成新", "几乎全新", "全新",
                "进过水", "维修过", "拆修", "更换过", "电池健康", "屏幕有"
        };
        StringBuilder condition = new StringBuilder();
        for (String segment : CONDITION_SEGMENT_SPLIT.split(lower)) {
            String sentence = segment.strip();
            if (sentence.isEmpty() || !containsAnyMarker(sentence, conditionMarkers)) {
                continue;
            }
            if (!condition.isEmpty()) {
                condition.append("；");
            }
            condition.append(sentence);
        }
        return condition.toString();
    }

    private static boolean containsAnyMarker(String sentence, String[] markers) {
        for (String marker : markers) {
            if (sentence.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 成色相关句子按标点切分；顿号/分号/句号/换行都视为句子边界。 */
    private static final Pattern CONDITION_SEGMENT_SPLIT =
            Pattern.compile("[\\n。；;，,]");

    private String stripLeadingTitle(String description, String title) {
        if (title == null || title.isBlank() || description == null) {
            return description == null ? "" : description;
        }
        String text = description.strip();
        if (!text.startsWith(title.strip())) {
            return text;
        }
        String stripped = text.substring(title.strip().length())
                .replaceFirst("^[\\s，。：:、,.;；\\-—]+", "")
                .strip();
        return stripped.isBlank() ? text : stripped;
    }

    // --- 覆盖层持久化：重启不回退“已售出/已改价” ---

    private Path overridesDir() {
        return Path.of(sidecarProperties.getItemSnapshotDir(), "overrides");
    }

    /** 覆盖值写盘：临时文件 + 原子替换，失败只降级为内存态（WARN），不影响交易链路。 */
    private void persistOverride(String itemId, ItemFactOverride override) {
        Path file = persistableFile(itemId);
        if (file == null) {
            // itemId 形如 "../etc" 的无法安全落盘：内存覆盖照常生效，重启丢失可接受
            // （能走到这里的调用方已被 Controller 校验，这是双保险）。
            LOGGER.warn("闲鱼覆盖层跳过落盘（itemId 非法）: item={}", itemId);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            ObjectNode node = JSON.createObjectNode();
            node.put("itemId", itemId);
            if (override.price() != null && override.price().known()) {
                node.put("priceMin", override.price().min());
                node.put("priceMax", override.price().max());
            }
            if (override.stock() != null) {
                node.put("stock", override.stock());
            }
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, node.toString());
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException exception) {
            LOGGER.warn("闲鱼覆盖层落盘失败（继续以内存态运行，重启将丢失该项）: item={}", itemId, exception);
        }
    }

    /** 启动恢复：整目录读取覆盖文件；单个文件损坏只跳过该项，不阻断启动。 */
    private void loadPersistedOverrides() {
        Path dir = overridesDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        int restored = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    JsonNode node = JSON.readTree(Files.readString(file));
                    String itemId = node.path("itemId").asText("");
                    if (itemId.isBlank() || persistableFile(itemId) == null) {
                        continue;
                    }
                    PriceRange price = null;
                    JsonNode min = node.path("priceMin");
                    JsonNode max = node.path("priceMax");
                    if (min.isNumber() && max.isNumber()) {
                        price = new PriceRange(min.decimalValue(), max.decimalValue());
                    }
                    Integer stock = node.path("stock").isNumber() ? node.path("stock").asInt() : null;
                    if (price == null && stock == null) {
                        continue;
                    }
                    liveOverrides.put(itemId, new ItemFactOverride(price, stock));
                    restored++;
                } catch (Exception perFile) {
                    LOGGER.warn("闲鱼覆盖层文件损坏，跳过: file={}", file, perFile);
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("闲鱼覆盖层目录读取失败，按无持久化启动", exception);
        }
        if (restored > 0) {
            LOGGER.info("闲鱼商品事实覆盖层已从磁盘恢复 {} 项", restored);
        }
    }

    /** 落盘文件路径；itemId 不满足安全字符集时返回 null（防路径穿越）。 */
    private Path persistableFile(String itemId) {
        String trimmed = itemId == null ? "" : itemId.trim();
        return PERSISTABLE_ITEM_ID.matcher(trimmed).matches()
                ? overridesDir().resolve(trimmed + ".json")
                : null;
    }

    /** 当前商品的结构化事实；价格/库存未知时显式表达，绝不折算为 0。 */
    public record ItemFacts(
            String itemId,
            String title,
            PriceRange price,
            int stock,
            String description,
            String condition
    ) {

        public boolean stockKnown() {
            return stock >= 0;
        }

        public boolean inStock() {
            return stock > 0;
        }
    }

    /** 价格区间；unknown 表示知识文档没有可靠价格，回复只能说“以商品页为准”。 */
    public record PriceRange(BigDecimal min, BigDecimal max) {

        static PriceRange unknown() {
            return new PriceRange(null, null);
        }

        public static PriceRange of(BigDecimal single) {
            return new PriceRange(single, single);
        }

        public boolean known() {
            return min != null && max != null;
        }

        public boolean singleValue() {
            return known() && min.compareTo(max) == 0;
        }

        /** 面向买家的展示文案：“¥265”或“¥265 - ¥280”；未知时返回空串交由调用方兜底。 */
        public String display() {
            if (!known()) {
                return "";
            }
            if (singleValue()) {
                return "¥" + min.stripTrailingZeros().toPlainString();
            }
            return "¥" + min.stripTrailingZeros().toPlainString()
                    + " - ¥" + max.stripTrailingZeros().toPlainString();
        }
    }
}
