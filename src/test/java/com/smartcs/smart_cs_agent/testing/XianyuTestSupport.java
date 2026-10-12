package com.smartcs.smart_cs_agent.testing;

import com.smartcs.smart_cs_agent.channel.xianyu.*;
import java.util.Optional;
import static org.mockito.Mockito.*;

public final class XianyuTestSupport {
    private XianyuTestSupport() { }
    public static XianyuSellerRuleService sellerRules(XianyuScenarioProperties properties) {
        var profiles = mock(XianyuItemProfileStore.class);
        when(profiles.published(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        return new XianyuSellerRuleService(properties, profiles);
    }
}
