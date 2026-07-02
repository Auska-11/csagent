package com.smartcs.smart_cs_agent.model;

/**
 * 产品实体类
 * 对应数据库中的产品表
 */
public class Product {

    private String productId;  // 产品ID
    private String name;       // 产品名称
    private int price;         // 价格（单位：元）
    private int stock;         // 库存数量
    private String desc;       // 产品描述

    public Product(String productId, String name, int price, int stock, String desc) {
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.desc = desc;
    }

    // Getter 方法
    public String getProductId() { return productId; }
    public String getName() { return name; }
    public int getPrice() { return price; }
    public int getStock() { return stock; }
    public String getDesc() { return desc; }

    @Override
    public String toString() {
        return "产品{" +
               "name='" + name + '\'' +
               ", price=" + price +
               ", stock=" + stock +
               ", desc='" + desc + '\'' +
               '}';
    }
}