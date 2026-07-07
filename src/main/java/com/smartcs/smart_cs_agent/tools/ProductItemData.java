package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartcs.smart_cs_agent.model.Product;

/**
 * 单个商品的结构化数据。
 *
 * 商品搜索和商品详情都会用到商品对象，所以把公共字段抽成一个 DTO。
 */
public class ProductItemData {

    private final String productId;
    private final String name;
    private final int price;
    private final int stock;
    private final boolean inStock;
    private final String description;

    public ProductItemData(Product product) {
        this.productId = product.getProductId();
        this.name = product.getName();
        this.price = product.getPrice();
        this.stock = product.getStock();
        this.inStock = product.getStock() > 0;
        this.description = product.getDesc();
    }

    @JsonProperty("product_id")
    public String getProductId() {
        return productId;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("price")
    public int getPrice() {
        return price;
    }

    @JsonProperty("stock")
    public int getStock() {
        return stock;
    }

    @JsonProperty("in_stock")
    public boolean isInStock() {
        return inStock;
    }

    @JsonProperty("description")
    public String getDescription() {
        return description;
    }
}
