package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 把侧车带来的商品快照落盘为知识文档草稿（X2 契约中的 itemSnapshot 分支）。
 *
 * <p>设计决策（doc-76 §5.2）：商品知识走文档而非塞进消息正文。知识索引在应用启动时
 * 从 classpath 一次性构建、没有热加载，所以这里只负责产出草稿——人工审校后移动到
 * src/main/resources/knowledge/product/ 并重启才生效；落盘失败只降级 status，不阻断会话注册。</p>
 */
@Component
public class XianyuItemKnowledgeWriter {

    public static final String STATUS_CREATED = "created";
    public static final String STATUS_EXISTING = "existing";
    public static final String STATUS_SKIPPED = "skipped";

    private static final Logger LOGGER = LoggerFactory.getLogger(XianyuItemKnowledgeWriter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** @return 草稿处理结果：created / existing / skipped（快照缺失、解析失败或写盘失败）。 */
    public String writeDraft(String itemId, String itemSnapshot, Path directory) {
        if (itemSnapshot == null || itemSnapshot.isBlank()) {
            return STATUS_SKIPPED;
        }
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(itemSnapshot);
        } catch (IOException exception) {
            LOGGER.warn("闲鱼商品快照不是合法 JSON（item={}），已跳过知识草稿生成", itemId);
            return STATUS_SKIPPED;
        }
        try {
            Path dir = directory.toAbsolutePath().normalize();
            Files.createDirectories(dir);
            // 原始快照始终以最新为准覆盖，便于人工复核与重新生成草稿。
            Files.writeString(dir.resolve(itemId + ".json"), itemSnapshot, StandardCharsets.UTF_8);
            Path draft = dir.resolve(itemId + ".md");
            if (Files.exists(draft)) {
                return STATUS_EXISTING;
            }
            Files.writeString(draft, renderDraft(itemId, snapshot), StandardCharsets.UTF_8);
            LOGGER.info("闲鱼商品知识草稿已生成: {}", draft);
            return STATUS_CREATED;
        } catch (IOException exception) {
            LOGGER.warn("闲鱼商品知识草稿落盘失败（item={}），会话不受影响", itemId, exception);
            return STATUS_SKIPPED;
        }
    }

    private String renderDraft(String itemId, JsonNode snapshot) {
        String title = text(snapshot, "title", "未命名商品");
        String desc = text(snapshot, "desc", "");
        String priceRange = text(snapshot, "price_range", "以商品页为准");
        String totalStock = text(snapshot, "total_stock", "未知");
        StringBuilder markdown = new StringBuilder()
                .append("---\n")
                .append("documentid: product/").append(itemId).append(".md\n")
                .append("knowledgetype: POLICY\n")
                .append("productcategory: xianyu\n")
                .append("source: xianyu-item-snapshot\n")
                .append("version: 1\n")
                .append("---\n\n")
                .append("# ").append(title).append("\n\n")
                .append("> 闲鱼商品快照自动生成（").append(LocalDate.now()).append("）。")
                .append("人工审校后移动到 src/main/resources/knowledge/product/ 并重启应用生效；")
                .append("本目录不会被自动索引。\n\n")
                .append("关键词：").append(title).append("、").append(itemId).append("\n\n")
                .append("## 基本信息\n\n")
                .append("- 商品 ID：").append(itemId).append("\n")
                .append("- 价格：").append(priceRange).append("\n")
                .append("- 库存：").append(totalStock).append("\n\n");
        if (!desc.isBlank()) {
            markdown.append("## 商品描述\n\n").append(desc.strip()).append("\n\n");
        }
        List<String> skuLines = renderSkus(snapshot);
        if (!skuLines.isEmpty()) {
            markdown.append("## 规格与库存\n\n");
            skuLines.forEach(line -> markdown.append("- ").append(line).append("\n"));
        }
        return markdown.toString();
    }

    private List<String> renderSkus(JsonNode snapshot) {
        List<String> lines = new ArrayList<>();
        JsonNode skus = snapshot.get("sku_details");
        if (skus == null || !skus.isArray()) {
            return lines;
        }
        for (JsonNode sku : skus) {
            String spec = text(sku, "spec", "默认规格");
            String price = text(sku, "price", "");
            String stock = text(sku, "stock", "");
            if (price.isBlank()) {
                lines.add(spec + "（库存 " + (stock.isBlank() ? "未知" : stock) + "）");
            } else {
                lines.add(spec + "：¥" + price + "（库存 " + (stock.isBlank() ? "未知" : stock) + "）");
            }
        }
        return lines;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? fallback : text;
    }
}
