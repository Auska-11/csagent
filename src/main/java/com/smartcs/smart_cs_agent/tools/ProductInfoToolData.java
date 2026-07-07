package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 商品详情工具的结构化输出 DTO。
 */
public class ProductInfoToolData {

    private final String query;
    private final int matchedCount;
    private final boolean found;
    private final ProductItemData product;

    public ProductInfoToolData(String query, int matchedCount, ProductItemData product) {
        this.query = query;
        this.matchedCount = matchedCount;
        this.found = product != null;
        this.product = product;
    }

    @JsonProperty("query")
    public String getQuery() {
        return query;
    }

    @JsonProperty("matched_count")
    public int getMatchedCount() {
        return matchedCount;
    }

    @JsonProperty("found")
    public boolean isFound() {
        return found;
    }

    @JsonProperty("product")
    public ProductItemData getProduct() {
        return product;
    }
}
