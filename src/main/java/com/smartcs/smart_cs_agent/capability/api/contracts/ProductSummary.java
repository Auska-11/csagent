package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.Map;

/**
 * 单个商品的稳定 Capability 输出契约。
 *
 * <p>旧本地工具使用 ProductItemData，远程 MCP 使用 JSON Map。Workflow 只依赖本类后，
 * Provider 的部署方式可以变化，而商品流程不需要跟着修改。</p>
 */
public final class ProductSummary {

    private final String productId;
    private final String name;
    private final int price;
    private final int stock;
    private final String description;

    public ProductSummary(String productId,
                          String name,
                          int price,
                          int stock,
                          String description) {
        this.productId = safeText(productId);
        this.name = safeText(name);
        this.price = Math.max(0, price);
        this.stock = Math.max(0, stock);
        this.description = safeText(description);
    }

    /** 将远程 MCP 返回的 snake_case 或 camelCase JSON Map 恢复为强类型契约。 */
    public static ProductSummary fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new ProductSummary(
                text(map, "product_id", "productId"),
                text(map, "name"),
                integer(map, "price"),
                integer(map, "stock"),
                text(map, "description", "desc")
        );
    }

    public String getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public boolean isInStock() {
        return stock > 0;
    }

    public String getDescription() {
        return description;
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static int integer(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
