package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 商品搜索工具的结构化输出 DTO。
 */
public class ProductSearchToolData {

    private final String keyword;
    private final int count;
    private final List<ProductItemData> products;

    public ProductSearchToolData(String keyword, List<ProductItemData> products) {
        this.keyword = keyword;
        this.products = products == null ? List.of() : List.copyOf(products);
        this.count = this.products.size();
    }

    @JsonProperty("keyword")
    public String getKeyword() {
        return keyword;
    }

    @JsonProperty("count")
    public int getCount() {
        return count;
    }

    @JsonProperty("products")
    public List<ProductItemData> getProducts() {
        return products;
    }
}
