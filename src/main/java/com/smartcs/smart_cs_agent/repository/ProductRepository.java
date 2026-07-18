package com.smartcs.smart_cs_agent.repository;

import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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

    private final ProductMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;

    public ProductRepository() {
        this.mapper = null;
        this.mapperExecutor = new MyBatisMapperExecutor();
        initMockData();
    }

    @Autowired
    public ProductRepository(ObjectProvider<ProductMapper> mapperProvider,
                             MyBatisMapperExecutor mapperExecutor) {
        this.mapper = mapperProvider.getIfAvailable();
        this.mapperExecutor = mapperExecutor;
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
        mapperExecutor.run(mapper::createTable);

        seedJdbc(new Product("PROD-001", "蓝牙耳机 Pro Max", 599, 100, "支持主动降噪，续航30小时，蓝牙5.3"));
        seedJdbc(new Product("PROD-002", "智能手表 S2", 1299, 50, "14天超长续航，5ATM防水，1.43英寸AMOLED屏"));
        seedJdbc(new Product("PROD-003", "无线充电器", 199, 200, "15W快充，兼容Qi协议，支持iPhone和安卓"));
        seedJdbc(new Product("PROD-004", "Type-C 数据线", 29, 500, "1米长度，支持60W快充，编织材质"));
    }

    private void seedJdbc(Product product) {
        mapperExecutor.execute(() -> mapper.upsert(product));
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
            return mapperExecutor.execute(() -> mapper.findByProductId(productId));
        }
        return productTable.get(productId);
    }

    /**
     * 根据名称关键词搜索（模糊匹配）
     */
    public List<Product> searchByName(String keyword) {
        if (isJdbcMode()) {
            return mapperExecutor.execute(() -> mapper.searchByName("%" + keyword + "%"));
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
            return mapperExecutor.execute(mapper::findAll);
        }
        return new ArrayList<>(productTable.values());
    }

    private boolean isJdbcMode() {
        return mapper != null;
    }
}
