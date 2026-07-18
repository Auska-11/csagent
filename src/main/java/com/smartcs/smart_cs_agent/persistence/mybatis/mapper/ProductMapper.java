package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.model.Product;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ProductMapper {

    void createTable();

    int upsert(Product product);

    Product findByProductId(@Param("productId") String productId);

    List<Product> searchByName(@Param("keywordPattern") String keywordPattern);

    List<Product> findAll();
}
