package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品相关工具类
 *
 * 【功能】
 * 1. queryProductInfo - 查询单个产品详情
 * 2. searchProducts - 根据关键词搜索产品列表
 *
 * 【设计思路】
 * - 查询详情：用户说 "蓝牙耳机多少钱" -> 返回价格、库存、描述
 * - 搜索列表：用户说 "有什么耳机" -> 返回匹配的产品列表
 */
@Component
public class ProductTools {

    private final ProductRepository productRepository;

    public ProductTools(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * 查询产品详情
     *
     * 【使用场景】
     * - "蓝牙耳机多少钱？"
     * - "智能手表有货吗？"
     * - "无线充电器的参数是什么？"
     *
     * @param productName 产品名称关键词
     * @return 产品详细信息（价格、库存、描述）
     */
    @Tool("query product details by name, return price, stock and description")
    public String queryProductInfo(
            @P("product name, like Bluetooth Headset, Smart Watch") String productName) {

        // 模糊搜索（包含关键词就匹配）
        List<Product> products = productRepository.searchByName(productName);

        if (products.isEmpty()) {
            return "Product not found: " + productName;
        }

        // 取第一个匹配的产品
        Product product = products.get(0);

        return "Product info:\n" +
               "- Name: " + product.getName() + "\n" +
               "- Price: " + product.getPrice() + " yuan\n" +
               "- Stock: " + (product.getStock() > 0 ? "In stock (" + product.getStock() + " units)" : "Out of stock") + "\n" +
               "- Description: " + product.getDesc();
    }

    /**
     * 查询单个商品结构化数据。
     */
    public ProductInfoToolData queryProductInfoData(String productName) {
        List<Product> products = productRepository.searchByName(productName);

        if (products.isEmpty()) {
            return new ProductInfoToolData(productName, 0, null);
        }

        return new ProductInfoToolData(productName, products.size(), new ProductItemData(products.get(0)));
    }

    /**
     * 搜索产品列表
     *
     * 【与 queryProductInfo 的区别】
     * - queryProductInfo：精确查询，返回单个产品详情
     * - searchProducts：模糊搜索，返回多个产品列表
     *
     * 【使用场景】
     * - "有什么耳机？"
     * - "搜索智能产品"
     *
     * @param keyword 搜索关键词
     * @return 匹配的产品列表
     */
    @Tool("search products by keyword, return matching product list")
    public String searchProducts(
            @P("search keyword") String keyword) {

        List<Product> products = productRepository.searchByName(keyword);

        if (products.isEmpty()) {
            return "No products found for: " + keyword;
        }

        // 拼接产品列表
        StringBuilder result = new StringBuilder();
        result.append("Found ").append(products.size()).append(" products:\n");

        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            result.append(i + 1).append(". ").append(product.getName());
            result.append(" | Price: ").append(product.getPrice()).append(" yuan");
            result.append(" | Stock: ").append(product.getStock() > 0 ? "In stock" : "Out of stock");

            if (i < products.size() - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 搜索商品结构化数据。
     */
    public ProductSearchToolData searchProductsData(String keyword) {
        List<Product> products = productRepository.searchByName(keyword);
        List<ProductItemData> productItems = new ArrayList<>();

        for (Product product : products) {
            productItems.add(new ProductItemData(product));
        }

        return new ProductSearchToolData(keyword, productItems);
    }
}
