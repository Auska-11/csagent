package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.PriceRange;
import com.smartcs.smart_cs_agent.knowledge.index.KnowledgeChunker;
import com.smartcs.smart_cs_agent.knowledge.index.MarkdownKnowledgeDocumentLoader;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports reviewed classpath item documents once. Exact item lookup never depends on RAG ranking. */
@Component
public class XianyuReviewedItemCatalog {
    private final Map<String, ItemFacts> items;

    @Autowired
    public XianyuReviewedItemCatalog() { this(new MarkdownKnowledgeDocumentLoader().load()); }

    public XianyuReviewedItemCatalog(List<KnowledgeDocument> documents) {
        Map<String, ItemFacts> parsed = new LinkedHashMap<>();
        for (KnowledgeDocument doc : documents) {
            if (!"xianyu".equals(doc.getChannel()) || !doc.getId().matches("product/[A-Za-z0-9_-]{1,64}\\.md")) continue;
            String itemId = doc.getId().substring(8, doc.getId().length() - 3);
            List<KnowledgeChunk> chunks = new KnowledgeChunker().chunk(doc);
            String content = String.join("\n", chunks.stream().map(KnowledgeChunk::content).toList());
            ItemFacts facts = new ItemFacts(itemId, doc.getTitle(), parsePrice(content), parseStock(content),
                    descriptionOf(chunks, doc.getTitle()), conditionOf(content), doc.getProductCategory(), doc.getProductModel(), "");
            if (parsed.putIfAbsent(itemId, facts) != null) throw new IllegalStateException("Duplicate reviewed item: " + itemId);
        }
        items = Map.copyOf(parsed);
    }

    public Optional<ItemFacts> find(String itemId) { return Optional.ofNullable(items.get(itemId)); }
    public Set<String> itemIds() { return items.keySet(); }

    private static final Pattern PRICE_RANGE_PATTERN = Pattern.compile(
            "价格[:：]\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:[-–~到]\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?))?");
    private static final Pattern STOCK_PATTERN = Pattern.compile("库存[:：]\\s*([0-9]+)");

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
            return min.signum() > 0 && max.signum() > 0 ? new PriceRange(min, max) : PriceRange.unknown();
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
    private String descriptionOf(List<KnowledgeChunk> hits, String title) {
        for (KnowledgeChunk hit : hits) {
            if (hit.title().contains("/ 商品描述")) {
                return stripLeadingTitle(hit.content(), title);
            }
        }
        for (KnowledgeChunk hit : hits) {
            if (!hit.title().contains("/ 基本信息")) {
                return stripLeadingTitle(hit.content(), title);
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

}
