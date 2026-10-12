package com.smartcs.smart_cs_agent.channel.xianyu;

import org.springframework.stereotype.Service;

/** Published per-item authorization wins over startup configuration. Internal prices never enter item facts. */
@Service
public class XianyuSellerRuleService {
    private final XianyuScenarioProperties properties;
    private final XianyuItemProfileStore profiles;

    public XianyuSellerRuleService(XianyuScenarioProperties properties, XianyuItemProfileStore profiles) {
        this.properties = properties;
        this.profiles = profiles;
    }

    public XianyuScenarioProperties.SellerRules forItem(String itemId) {
        return profiles.published(itemId).map(profile -> profile.sellerPolicy().toRules())
                .orElseGet(() -> properties.sellerRulesFor(itemId));
    }
}
