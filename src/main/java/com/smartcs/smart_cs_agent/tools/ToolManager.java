package com.smartcs.smart_cs_agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ToolManager {

    private final OrderTools orderTools;
    private final ProductTools productTools;
    private final ReturnTools returnTools;
    private final UserTools userTools;

    public ToolManager(OrderTools orderTools, ProductTools productTools,
                       ReturnTools returnTools, UserTools userTools) {
        this.orderTools = orderTools;
        this.productTools = productTools;
        this.returnTools = returnTools;
        this.userTools = userTools;
    }

    public Object execute(String capability, Map<String, Object> arguments) {
        switch (capability) {
            case "order_lookup":
                return orderTools.queryOrderStatus((String) arguments.get("order_id"));
            case "user_orders":
                return orderTools.queryUserOrders((String) arguments.get("user_id"));
            case "product_search":
                return productTools.searchProducts((String) arguments.get("keyword"));
            case "product_info":
                return productTools.queryProductInfo((String) arguments.get("product_name"));
            case "return_apply":
                return returnTools.applyReturn(
                        (String) arguments.get("order_id"),
                        (String) arguments.getOrDefault("reason", "User requested return"));
            case "return_query":
                return returnTools.queryReturnStatus((String) arguments.get("return_id"));
            case "user_lookup":
                return userTools.queryUserInfo((String) arguments.getOrDefault("token", arguments.get("user_id")));
            default:
                return "Unknown capability: " + capability;
        }
    }
}
