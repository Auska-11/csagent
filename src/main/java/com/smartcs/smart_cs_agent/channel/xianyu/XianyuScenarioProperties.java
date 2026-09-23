package com.smartcs.smart_cs_agent.channel.xianyu;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闲鱼渠道的业务场景配置：一个租户一个入口，集中声明“哪些真实业务流程开放”和
 * “卖家授权了什么商业规则”。
 *
 * <p>为什么不用散落的 {@code tenantId == xianyu} 判断：本类把两类决策收敛到同一处——</p>
 * <ul>
 *     <li>{@link #excludedWorkflows}：路由边界。演示商城流程（订单、退货、发票政策等）
 *         即使关键词命中也不得进入真实买家会话，Router 侧统一跳过。</li>
 *     <li>{@link #sellerRules}：议价授权。报价、包邮等承诺只能来自这里的显式配置；
 *         没有配置就是“未授权”，回复必须引导卖家确认，模型不得自行发挥。</li>
 * </ul>
 *
 * <p>所有金额都用 {@link BigDecimal} 并保留原始字符串：二手交易常见 265.5 这类价格，
 * 取整或 double 运算都会向买家报错价。底线价（floorPrice）是内部授权上限，任何回复
 * 都不能把它透露给买家。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.xianyu.scenario")
public class XianyuScenarioProperties {

    /**
     * 闲鱼会话禁入的 Workflow ID 清单。默认排除全部“模拟商城”流程：
     * 订单查询/退货申请/退货进度/保修（这些依赖演示库 ORD-000 数据或演示售后规则）。
     * 转人工 escalation 不排除——它是真实渠道的合法出口。
     */
    private Set<String> excludedWorkflows = new LinkedHashSet<>(List.of(
            "order_query", "return_apply", "return_query", "warranty_query"
    ));

    /** 卖家商业规则；未配置时议价等承诺一律按“需卖家确认”处理。 */
    private SellerRules sellerRules = new SellerRules();

    /**
     * 按商品绑定的卖家规则覆盖（itemId -> 规则）。同一卖家可能只授权某一件商品让价；
     * 未绑定的商品回落到全局 {@link #sellerRules}。
     */
    private Map<String, SellerRules> itemRules = new LinkedHashMap<>();

    public Set<String> getExcludedWorkflows() {
        return excludedWorkflows;
    }

    public void setExcludedWorkflows(Set<String> excludedWorkflows) {
        this.excludedWorkflows = excludedWorkflows == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(excludedWorkflows);
    }

    public SellerRules getSellerRules() {
        return sellerRules;
    }

    public void setSellerRules(SellerRules sellerRules) {
        this.sellerRules = sellerRules == null ? new SellerRules() : sellerRules;
    }

    public Map<String, SellerRules> getItemRules() {
        return itemRules;
    }

    public void setItemRules(Map<String, SellerRules> itemRules) {
        this.itemRules = itemRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(itemRules);
    }

    /** 取某商品生效的卖家规则：按商品绑定优先，未绑定回落全局。 */
    public SellerRules sellerRulesFor(String itemId) {
        if (itemId != null && !itemId.isBlank()) {
            SellerRules rules = itemRules.get(itemId.trim());
            if (rules != null) {
                return rules;
            }
        }
        return sellerRules;
    }

    /** 该 Workflow 是否被本渠道场景排除（Router 首次评分时调用）。 */
    public boolean isWorkflowExcluded(String workflowId) {
        return workflowId != null && excludedWorkflows.contains(workflowId.trim());
    }

    /** 卖家维度议价与发货规则。全部字段可缺省；缺省 = 未授权，不代表“拒绝”。 */
    public static class SellerRules {

        /**
         * 是否接受议价。false 或未配置时，买家出价只能回复“需卖家本人确认”，
         * 既不能答应也不能一口回绝报出理由。
         */
        private boolean bargainAccepted = false;

        /**
         * 授权议价空间：买家出价不低于 floorPrice 时可由 Agent 直接答应。
         * 内部字段，绝不写入给买家的回复。
         */
        private BigDecimal floorPrice;

        /** 一次还价的固定降价额（元）。配置后 Agent 可用“让 X 元”回应；未配置则不主动让价。 */
        private BigDecimal allowedDiscount;

        /** 包邮条件描述，如“满 200 包邮”。未配置时对包邮请求只能转卖家确认。 */
        private String freeShippingCondition = "";

        /** 发货安排描述，如“顺丰到付 / 48 小时内发”。未配置时不虚构发货承诺。 */
        private String shippingArrangement = "";

        public boolean isBargainAccepted() {
            return bargainAccepted;
        }

        public void setBargainAccepted(boolean bargainAccepted) {
            this.bargainAccepted = bargainAccepted;
        }

        public BigDecimal getFloorPrice() {
            return floorPrice;
        }

        public void setFloorPrice(BigDecimal floorPrice) {
            this.floorPrice = floorPrice;
        }

        public BigDecimal getAllowedDiscount() {
            return allowedDiscount;
        }

        public void setAllowedDiscount(BigDecimal allowedDiscount) {
            this.allowedDiscount = allowedDiscount;
        }

        public String getFreeShippingCondition() {
            return freeShippingCondition == null ? "" : freeShippingCondition.trim();
        }

        public void setFreeShippingCondition(String freeShippingCondition) {
            this.freeShippingCondition = freeShippingCondition;
        }

        public String getShippingArrangement() {
            return shippingArrangement == null ? "" : shippingArrangement.trim();
        }

        public void setShippingArrangement(String shippingArrangement) {
            this.shippingArrangement = shippingArrangement == null ? "" : shippingArrangement;
        }
    }

    /** 测试辅助：全默认（未授权任何规则、排除默认商城流程）的场景配置。 */
    public static XianyuScenarioProperties defaults() {
        return new XianyuScenarioProperties();
    }

    /** 测试辅助：按演示用例构造场景配置。 */
    public static XianyuScenarioProperties of(Set<String> excludedWorkflows, SellerRules sellerRules) {
        XianyuScenarioProperties properties = new XianyuScenarioProperties();
        properties.setExcludedWorkflows(new LinkedHashSet<>(excludedWorkflows));
        properties.setSellerRules(sellerRules);
        return properties;
    }

    /** 供 YAML 示例与文档引用的默认排除清单（防止配置漂移）。 */
    public static List<String> defaultExcludedWorkflowIds() {
        return List.of("order_query", "return_apply", "return_query", "warranty_query");
    }

    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("excludedWorkflows", List.copyOf(excludedWorkflows));
        description.put("bargainAccepted", sellerRules.bargainAccepted);
        description.put("floorPriceConfigured", sellerRules.floorPrice != null);
        description.put("allowedDiscountConfigured", sellerRules.allowedDiscount != null);
        description.put("freeShippingConditionConfigured", !sellerRules.getFreeShippingCondition().isEmpty());
        description.put("shippingArrangementConfigured", !sellerRules.getShippingArrangement().isEmpty());
        description.put("itemRuleCount", itemRules.size());
        return description;
    }
}
