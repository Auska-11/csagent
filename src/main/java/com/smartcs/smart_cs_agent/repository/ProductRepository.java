package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Product;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 产品数据仓库
 */
@Repository
public class ProductRepository {

    // 模拟产品表：productId -> Product 对象
    private final Map<String, Product> productTable = new HashMap<>();

    public ProductRepository() {
        initMockData();
    }

    private void initMockData() {
        save(new Product("PROD-001", "蓝牙耳机 Pro Max", 599, 100, "支持主动降噪，续航30小时，蓝牙5.3"));
        save(new Product("PROD-002", "智能手表 S2", 1299, 50, "14天超长续航，5ATM防水，1.43英寸AMOLED屏"));
        save(new Product("PROD-003", "无线充电器", 199, 200, "15W快充，兼容Qi协议，支持iPhone和安卓"));
        save(new Product("PROD-004", "Type-C 数据线", 29, 500, "1米长度，支持60W快充，编织材质"));
    }

    public void save(Product product) {
        productTable.put(product.getProductId(), product);
    }

    /**
     * 根据产品ID查询
     */
    public Product findByProductId(String productId) {
        return productTable.get(productId);
    }

    /**
     * 根据名称关键词搜索（模糊匹配）
     */
    public List<Product> searchByName(String keyword) {
        return productTable.values().stream()
            .filter(p -> p.getName().contains(keyword))
            .collect(Collectors.toList());
    }

    /**
     * 获取所有产品
     */
    public List<Product> findAll() {
        return new ArrayList<>(productTable.values());
    }
}