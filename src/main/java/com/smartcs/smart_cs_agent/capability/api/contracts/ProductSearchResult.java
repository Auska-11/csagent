package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** product.search 的稳定列表契约。 */
public final class ProductSearchResult {

    private final String keyword;
    private final List<ProductSummary> products;

    public ProductSearchResult(String keyword, List<ProductSummary> products) {
        this.keyword = keyword == null ? "" : keyword;
        this.products = products == null ? List.of() : List.copyOf(products);
    }

    /** 将远程 MCP JSON 中的商品数组逐项恢复为 ProductSummary。 */
    public static ProductSearchResult fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        List<ProductSummary> products = new ArrayList<>();
        Object rows = map.get("products");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                ProductSummary product = row instanceof ProductSummary summary
                        ? summary
                        : ProductSummary.fromMap(row);
                if (product != null) {
                    products.add(product);
                }
            }
        }
        Object keyword = map.get("keyword");
        return new ProductSearchResult(keyword == null ? "" : keyword.toString(), products);
    }

    public String getKeyword() {
        return keyword;
    }

    public int getCount() {
        return products.size();
    }

    public List<ProductSummary> getProducts() {
        return products;
    }

    public boolean isEmpty() {
        return products.isEmpty();
    }
}
