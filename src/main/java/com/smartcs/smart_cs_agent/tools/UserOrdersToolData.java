package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 用户订单列表工具的结构化输出 DTO。
 *
 * 这个 DTO 包一层 userId、count 和 orders，
 * 比直接返回 List 更适合后续做 Trace、评测和前端展示。
 */
public class UserOrdersToolData {

    private final String userId;
    private final int count;
    private final List<OrderToolData> orders;

    public UserOrdersToolData(String userId, List<OrderToolData> orders) {
        this.userId = userId;
        this.orders = orders == null ? List.of() : List.copyOf(orders);
        this.count = this.orders.size();
    }

    @JsonProperty("user_id")
    public String getUserId() {
        return userId;
    }

    @JsonProperty("count")
    public int getCount() {
        return count;
    }

    @JsonProperty("orders")
    public List<OrderToolData> getOrders() {
        return orders;
    }
}
