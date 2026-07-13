package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品目录的独立本地 Provider。
 *
 * <p>职责：把 ProductRepository 的实体转换为稳定 ProductSummary/ProductSearchResult。</p>
 *
 * <p>不负责：不判断用户是不是在询价、不读取用户偏好、不组织客服话术。这些属于 Workflow。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalProductCatalogCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-product-catalog";

    private final ProductRepository productRepository;
    private final List<CapabilityDefinition> definitions;

    public LocalProductCatalogCapabilityProvider(ProductRepository productRepository) {
        this.productRepository = productRepository;
        this.definitions = List.of(searchDefinition(), lookupDefinition());
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        return definitions;
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String capabilityId = CapabilityIds.canonicalize(call.getCapabilityId());
        if (CapabilityIds.PRODUCT_SEARCH.equals(capabilityId)) {
            return search(call);
        }
        if (CapabilityIds.PRODUCT_LOOKUP.equals(capabilityId)) {
            return lookup(call);
        }
        return CapabilityResult.technicalFailure(
                capabilityId,
                PROVIDER_ID,
                "CAPABILITY_NOT_SUPPORTED",
                "Product catalog provider does not support this capability"
        );
    }

    private CapabilityResult<ProductSearchResult> search(CapabilityCall call) {
        String keyword = textArgument(call, "keyword");
        List<ProductSummary> products = productRepository.searchByName(keyword).stream()
                .map(this::toSummary)
                .toList();
        return CapabilityResult.success(
                CapabilityIds.PRODUCT_SEARCH,
                PROVIDER_ID,
                "Found " + products.size() + " matching products",
                new ProductSearchResult(keyword, products)
        );
    }

    private CapabilityResult<?> lookup(CapabilityCall call) {
        String productName = textArgument(call, "product_name");
        if (productName.isBlank()) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.PRODUCT_LOOKUP,
                    PROVIDER_ID,
                    "MISSING_ARGUMENT",
                    "product_name is required",
                    null
            );
        }

        List<Product> products = productRepository.searchByName(productName);
        if (products.isEmpty()) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.PRODUCT_LOOKUP,
                    PROVIDER_ID,
                    "PRODUCT_NOT_FOUND",
                    "Product was not found",
                    null
            );
        }
        return CapabilityResult.success(
                CapabilityIds.PRODUCT_LOOKUP,
                PROVIDER_ID,
                "Product found",
                toSummary(products.get(0))
        );
    }

    private ProductSummary toSummary(Product product) {
        return new ProductSummary(
                product.getProductId(),
                product.getName(),
                product.getPrice(),
                product.getStock(),
                product.getDesc()
        );
    }

    private String textArgument(CapabilityCall call, String key) {
        Object value = call.getArguments().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private CapabilityDefinition searchDefinition() {
        return new CapabilityDefinition(
                CapabilityIds.PRODUCT_SEARCH,
                "1.0.0",
                "按关键词搜索商品；空关键词表示列出全部商品",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "商品关键词，可为空")
                        )
                ),
                productSearchOutputSchema(),
                false,
                false,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }

    private CapabilityDefinition lookupDefinition() {
        return new CapabilityDefinition(
                CapabilityIds.PRODUCT_LOOKUP,
                "1.0.0",
                "查询单个商品的价格、库存和描述",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "product_name", Map.of("type", "string", "description", "商品名称或关键词")
                        ),
                        "required", List.of("product_name")
                ),
                productOutputSchema(),
                false,
                false,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }

    private Map<String, Object> productSearchOutputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string"),
                        "count", Map.of("type", "integer"),
                        "products", Map.of("type", "array", "items", productOutputSchema())
                )
        );
    }

    private Map<String, Object> productOutputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "product_id", Map.of("type", "string"),
                        "name", Map.of("type", "string"),
                        "price", Map.of("type", "integer"),
                        "stock", Map.of("type", "integer"),
                        "description", Map.of("type", "string")
                )
        );
    }
}
