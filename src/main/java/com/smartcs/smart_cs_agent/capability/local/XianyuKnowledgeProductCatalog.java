package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchProfile;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 闲鱼租户的商品目录视图：以审校入库的商品知识文档（productcategory=xianyu）为数据源。
 *
 * <p>演示租户的商品目录来自 ProductRepository 种子数据。把它原样提供给闲鱼买家，会报出
 * 与真实商品不符的价格和库存（X3 实测：真实挂件 ¥265 被演示目录报价 ¥599），语义上无法
 * 向买家交代——与 X4 屏蔽 return.apply 是同一类“演示数据不进真实渠道”问题。X2 已把商品
 * 快照落成知识文档，因此 xianyu 租户的 product.search / product.lookup 改为检索这些文档，
 * 并恢复成与演示目录相同的 ProductSummary 契约，Workflow 与调用方不感知数据源差异。</p>
 *
 * <p>价格和库存从 {@code XianyuItemKnowledgeWriter} 生成的固定字段行解析；人工审校商品文档时
 * 必须保留“## 基本信息”中的“价格：”“库存：”两行。解析失败退化为 0——宁可缺数字也不虚构
 * 商品信息。空关键词返回空列表：知识检索没有“列出全部”的入口，闲鱼会话以具体商品咨询为主，
 * v1 接受该边界。</p>
 */
class XianyuKnowledgeProductCatalog {

    static final String PRODUCT_CATEGORY = "xianyu";

    /** 单次回复最多展示的商品数，避免多商品卖家刷屏。 */
    private static final int MAX_PRODUCTS = 5;
    private static final int TOP_K = 10;

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("价格[:：]\\s*¥?([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern STOCK_PATTERN =
            Pattern.compile("库存[:：]\\s*([0-9]+)");
    private static final Pattern ITEM_ID_IN_CONTENT =
            Pattern.compile("商品\\s*ID[:：]\\s*([0-9]+)");

    private final KnowledgeTools knowledgeTools;

    XianyuKnowledgeProductCatalog(KnowledgeTools knowledgeTools) {
        this.knowledgeTools = knowledgeTools;
    }

    /** @return 按检索相关度排序的商品摘要；知识没有命中时为空列表（真正的目录 miss）。 */
    List<ProductSummary> search(String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.isBlank()) {
            return List.of();
        }
        List<KnowledgeSearchResult> hits = knowledgeTools.search(
                query,
                TOP_K,
                new KnowledgeSearchProfile(KnowledgeType.POLICY, PRODUCT_CATEGORY, "", "")
        );
        Map<String, List<KnowledgeSearchResult>> byDocument = new LinkedHashMap<>();
        for (KnowledgeSearchResult hit : hits) {
            byDocument.computeIfAbsent(hit.getDocumentId(), id -> new ArrayList<>()).add(hit);
        }
        return byDocument.values().stream()
                .limit(MAX_PRODUCTS)
                .map(this::toProduct)
                .toList();
    }

    /** @return 首个匹配商品；没有命中时返回 null，由调用方按 PRODUCT_NOT_FOUND 收尾。 */
    ProductSummary lookup(String productName) {
        List<ProductSummary> products = search(productName);
        return products.isEmpty() ? null : products.get(0);
    }

    /**
     * 同一商品文档的多个 Chunk 拼成一份摘要：名称取文档标题（Chunk 标题的“文档 / 小节”前缀），
     * 描述优先取“商品描述”小节，价格和库存从该文档任意 Chunk 的固定字段行解析。
     */
    private ProductSummary toProduct(List<KnowledgeSearchResult> hits) {
        String chunkTitle = hits.get(0).getTitle();
        int separator = chunkTitle.indexOf(" / ");
        String name = separator > 0 ? chunkTitle.substring(0, separator) : chunkTitle;
        String combined = String.join(" ", hits.stream()
                .map(KnowledgeSearchResult::getSnippet)
                .toList());
        return new ProductSummary(
                itemId(hits.get(0).getDocumentId(), combined),
                name,
                firstNumber(combined, PRICE_PATTERN),
                firstNumber(combined, STOCK_PATTERN),
                description(hits, name)
        );
    }

    /** 文档 ID 形如 product/{itemId}.md；不匹配时回退到正文中的“商品 ID”行。 */
    private String itemId(String documentId, String combinedSnippets) {
        if (documentId != null && documentId.startsWith("product/") && documentId.endsWith(".md")) {
            String itemId = documentId.substring("product/".length(), documentId.length() - ".md".length());
            if (!itemId.isBlank()) {
                return itemId;
            }
        }
        Matcher matcher = ITEM_ID_IN_CONTENT.matcher(combinedSnippets);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int firstNumber(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 描述候选去掉与商品名相同的开头再返回：快照的 desc 常以标题开头（"森海塞尔MOMENTUM二代
     * 头戴耳机森海塞尔MOMENTUM 2代…"），formatProducts 在外层又打印一次名称，逐字重复观感差。
     * 只剥<strong>完全相同</strong>的前缀；标题中途出现或仅部分重合时不动。
     */
    private String description(List<KnowledgeSearchResult> hits, String productName) {
        for (KnowledgeSearchResult hit : hits) {
            if (hit.getTitle().contains("/ 商品描述")) {
                return stripLeadingName(hit.getSnippet(), productName);
            }
        }
        for (KnowledgeSearchResult hit : hits) {
            if (!hit.getTitle().contains("/ 基本信息")) {
                return stripLeadingName(hit.getSnippet(), productName);
            }
        }
        return stripLeadingName(hits.get(0).getSnippet(), productName);
    }

    /** 剥离描述开头与商品名完全相同的部分；名称为空或开头不匹配时原样返回。 */
    private String stripLeadingName(String description, String productName) {
        if (productName == null || productName.isBlank()) {
            return description;
        }
        String prefix = productName.strip();
        String text = description == null ? "" : description;
        if (!text.startsWith(prefix)) {
            return text;
        }
        String stripped = text.substring(prefix.length());
        if (stripped.isBlank()) {
            // 描述只有名称本身，保留原文避免出现空描述。
            return text;
        }
        return stripped.strip();
    }
}
