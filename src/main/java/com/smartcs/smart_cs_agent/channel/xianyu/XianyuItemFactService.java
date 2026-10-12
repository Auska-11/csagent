package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Current item facts: reviewed profiles plus durable live price/stock. All consumers share this source. */
@Component
public class XianyuItemFactService {

    private static final Pattern CONVERSATION_ITEM_ID =
            Pattern.compile("^xianyu_[A-Za-z0-9_-]{1,64}_([A-Za-z0-9_-]{1,64})$");

    private final XianyuReviewedItemCatalog catalog;
    private final XianyuItemProfileStore profiles;
    private final XianyuSidecarProperties sidecarProperties;

    /** Durable live prices/stock have higher priority than reviewed prose. */
    private final ConcurrentHashMap<String, ItemFactOverride> liveOverrides = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(XianyuItemFactService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PERSISTABLE_ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 覆盖层写盘串行化：更新频率是“改价/售出”级别，全局锁没有竞争问题。 */
    private final Object persistLock = new Object();

    public XianyuItemFactService(XianyuReviewedItemCatalog catalog, XianyuItemProfileStore profiles,
                                 XianyuSidecarProperties sidecarProperties) {
        this.catalog = catalog;
        this.profiles = profiles;
        this.sidecarProperties = sidecarProperties;
        loadPersistedOverrides();
    }

    /** 热更新的商品事实：价格与库存均可为 null 表示“本次推送不变”。 */
    public record ItemFactOverride(PriceRange price, Integer stock) {
        public ItemFactOverride {
            if (stock != null && stock < 0) throw new IllegalArgumentException("Live stock must be >= 0");
            if (price != null && (!price.known() || price.min().signum() <= 0
                    || price.max().compareTo(price.min()) < 0)) {
                throw new IllegalArgumentException("Invalid live price range");
            }
        }
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
            ItemFactOverride current = liveOverrides.get(key);
            ItemFactOverride merged = new ItemFactOverride(price != null ? price : current == null ? null : current.price(),
                    stock != null ? stock : current == null ? null : current.stock());
            persistOverride(key, merged);
            liveOverrides.put(key, merged);
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
            persistOverride(key, new ItemFactOverride(price, stock));
            liveOverrides.put(key, new ItemFactOverride(price, stock));
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
     * 读取审核资料并合并平台现势价格与库存。未审核的描述不参与回答，未知字段保持未知。
     */
    public Optional<ItemFacts> itemFacts(String itemId) {
        if (itemId == null || itemId.isBlank()) return Optional.empty();
        String id = itemId.trim();
        ItemFacts base = profiles.published(id).map(profile -> profile.toFacts(id))
                .or(() -> catalog.find(id)).orElse(null);
        ItemFactOverride live = liveOverrides.get(id);
        if (base == null && live == null) return Optional.empty();
        if (base == null) base = new ItemFacts(id, "", PriceRange.unknown(), -1, "", "");
        ItemFacts imported = catalog.find(id).orElse(null);
        if (imported != null && !base.price().known()) {
            base = new ItemFacts(id, base.title(), imported.price(), imported.stock(), base.description(), base.condition(),
                    base.productCategory(), base.productModel(), base.accessories());
        }
        // Publishing prose cannot roll back live prices or stock, including stock=0.
        PriceRange price = live != null && live.price() != null ? live.price() : base.price();
        int stock = live != null && live.stock() != null ? live.stock() : base.stock();
        return Optional.of(new ItemFacts(id, base.title(), price, stock, base.description(), base.condition(),
                base.productCategory(), base.productModel(), base.accessories()));
    }

    public List<ItemFacts> listedItems() {
        java.util.Set<String> ids = new java.util.TreeSet<>(catalog.itemIds());
        ids.addAll(profiles.itemIds());
        ids.addAll(liveOverrides.keySet());
        return ids.stream().map(this::itemFacts).flatMap(Optional::stream).toList();
    }

    public boolean isXianyuTenant(String tenantId) {
        return tenantId != null && sidecarProperties.getTenantId().equalsIgnoreCase(tenantId.trim());
    }

    /** 便捷入口：直接按会话 ID 取当前商品事实。 */
    public Optional<ItemFacts> currentItemFacts(String conversationId) {
        return currentItemId(conversationId).flatMap(this::itemFacts);
    }

    // --- 覆盖层持久化：重启不回退“已售出/已改价” ---

    private Path overridesDir() {
        return Path.of(sidecarProperties.getItemSnapshotDir(), "overrides");
    }

    /** Persist before acknowledgment. A failed write keeps the previous confirmed state. */
    private void persistOverride(String itemId, ItemFactOverride override) {
        Path file = persistableFile(itemId);
        if (file == null) {
            throw new IllegalArgumentException("Invalid itemId");
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
            throw new IllegalStateException("Live item facts could not be saved", exception);
        }
    }

    /** 启动恢复；损坏的事实文件阻断启动，避免已售商品回退到旧库存。 */
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
                    if (persistableFile(itemId) == null || !file.getFileName().toString().equals(itemId + ".json")) {
                        throw new IllegalArgumentException("Live facts file does not match its itemId");
                    }
                    PriceRange price = null;
                    JsonNode min = node.path("priceMin");
                    JsonNode max = node.path("priceMax");
                    if (min.isNumber() && max.isNumber()) {
                        price = new PriceRange(min.decimalValue(), max.decimalValue());
                    } else if (!min.isMissingNode() || !max.isMissingNode()) {
                        throw new IllegalArgumentException("Invalid persisted price");
                    }
                    JsonNode stockNode = node.path("stock");
                    if (!stockNode.isMissingNode() && (!stockNode.isIntegralNumber() || !stockNode.canConvertToInt())) {
                        throw new IllegalArgumentException("Invalid persisted stock");
                    }
                    Integer stock = stockNode.isMissingNode() ? null : stockNode.asInt();
                    if (price == null && stock == null) {
                        throw new IllegalArgumentException("Empty persisted live facts");
                    }
                    liveOverrides.put(itemId, new ItemFactOverride(price, stock));
                    restored++;
                } catch (Exception perFile) {
                    throw new IllegalStateException("Live item facts could not be restored: " + file.getFileName(), perFile);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Live item facts directory could not be read", exception);
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
            String condition,
            String productCategory,
            String productModel,
            String accessories
    ) {
        public ItemFacts(String itemId, String title, PriceRange price, int stock, String description, String condition) {
            this(itemId, title, price, stock, description, condition, "", "", "");
        }

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
