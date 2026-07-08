package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final JdbcDatabaseClient databaseClient;

    public ProductRepository() {
        this(null);
    }

    @Autowired
    public ProductRepository(JdbcDatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
        if (isJdbcMode()) {
            initJdbcTable();
        } else {
            initMockData();
        }
    }

    private void initMockData() {
        save(new Product("PROD-001", "蓝牙耳机 Pro Max", 599, 100, "支持主动降噪，续航30小时，蓝牙5.3"));
        save(new Product("PROD-002", "智能手表 S2", 1299, 50, "14天超长续航，5ATM防水，1.43英寸AMOLED屏"));
        save(new Product("PROD-003", "无线充电器", 199, 200, "15W快充，兼容Qi协议，支持iPhone和安卓"));
        save(new Product("PROD-004", "Type-C 数据线", 29, 500, "1米长度，支持60W快充，编织材质"));
    }

    private void initJdbcTable() {
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_product (
                    product_id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    price INT NOT NULL,
                    stock INT NOT NULL,
                    product_desc TEXT
                )
                """);

        seedJdbc(new Product("PROD-001", "蓝牙耳机 Pro Max", 599, 100, "支持主动降噪，续航30小时，蓝牙5.3"));
        seedJdbc(new Product("PROD-002", "智能手表 S2", 1299, 50, "14天超长续航，5ATM防水，1.43英寸AMOLED屏"));
        seedJdbc(new Product("PROD-003", "无线充电器", 199, 200, "15W快充，兼容Qi协议，支持iPhone和安卓"));
        seedJdbc(new Product("PROD-004", "Type-C 数据线", 29, 500, "1米长度，支持60W快充，编织材质"));
    }

    private void seedJdbc(Product product) {
        databaseClient.update("""
                INSERT INTO cs_product(product_id, name, price, stock, product_desc)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    price = VALUES(price),
                    stock = VALUES(stock),
                    product_desc = VALUES(product_desc)
                """,
                product.getProductId(),
                product.getName(),
                product.getPrice(),
                product.getStock(),
                product.getDesc());
    }

    public void save(Product product) {
        if (isJdbcMode()) {
            seedJdbc(product);
            return;
        }
        productTable.put(product.getProductId(), product);
    }

    /**
     * 根据产品ID查询
     */
    public Product findByProductId(String productId) {
        if (isJdbcMode()) {
            return databaseClient.queryOne("""
                            SELECT product_id, name, price, stock, product_desc
                            FROM cs_product
                            WHERE product_id = ?
                            """,
                    resultSet -> new Product(
                            resultSet.getString("product_id"),
                            resultSet.getString("name"),
                            resultSet.getInt("price"),
                            resultSet.getInt("stock"),
                            resultSet.getString("product_desc")
                    ),
                    productId).orElse(null);
        }
        return productTable.get(productId);
    }

    /**
     * 根据名称关键词搜索（模糊匹配）
     */
    public List<Product> searchByName(String keyword) {
        if (isJdbcMode()) {
            return databaseClient.queryList("""
                            SELECT product_id, name, price, stock, product_desc
                            FROM cs_product
                            WHERE name LIKE ?
                            ORDER BY product_id
                            """,
                    resultSet -> new Product(
                            resultSet.getString("product_id"),
                            resultSet.getString("name"),
                            resultSet.getInt("price"),
                            resultSet.getInt("stock"),
                            resultSet.getString("product_desc")
                    ),
                    "%" + keyword + "%");
        }
        return productTable.values().stream()
            .filter(p -> p.getName().contains(keyword))
            .collect(Collectors.toList());
    }

    /**
     * 获取所有产品
     */
    public List<Product> findAll() {
        if (isJdbcMode()) {
            return databaseClient.queryList("""
                            SELECT product_id, name, price, stock, product_desc
                            FROM cs_product
                            ORDER BY product_id
                            """,
                    resultSet -> new Product(
                            resultSet.getString("product_id"),
                            resultSet.getString("name"),
                            resultSet.getInt("price"),
                            resultSet.getInt("stock"),
                            resultSet.getString("product_desc")
                    ));
        }
        return new ArrayList<>(productTable.values());
    }

    private boolean isJdbcMode() {
        return databaseClient != null && databaseClient.isEnabled();
    }
}
