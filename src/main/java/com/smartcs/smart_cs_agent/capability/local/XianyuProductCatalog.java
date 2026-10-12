package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import java.util.List;
import java.util.Locale;

/** Catalog adapter over the same authoritative facts used by quotation and sold-item guards. */
final class XianyuProductCatalog {
    private final XianyuItemFactService facts;
    XianyuProductCatalog(XianyuItemFactService facts) { this.facts = facts; }

    List<ProductSummary> search(String keyword) {
        String query = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        if (query.isBlank()) return List.of();
        return facts.listedItems().stream()
                .filter(item -> (item.itemId() + " " + item.title() + " " + item.description()).toLowerCase(Locale.ROOT).contains(query))
                .limit(5).map(item -> new ProductSummary(item.itemId(), item.title(),
                        item.price().singleValue() ? item.price().min() : null, item.stock(), item.description())).toList();
    }

    ProductSummary lookup(String name) {
        List<ProductSummary> matches = search(name);
        return matches.stream().filter(item -> item.getProductId().equals(name) || item.getName().equalsIgnoreCase(name))
                .findFirst().orElse(matches.size() == 1 ? matches.get(0) : null);
    }
}
