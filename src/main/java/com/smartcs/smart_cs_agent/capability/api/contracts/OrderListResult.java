package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code order.list-by-user} 的稳定输出契约。
 *
 * <p>userId 表示服务端实际查询的身份，orders 是该身份可见的订单。调用者不能通过普通工具参数
 * 指定另一个 userId；本地 Provider 会以 ExecutionContext.principal 为准。</p>
 */
public final class OrderListResult {

    private final String userId;
    private final List<OrderSummary> orders;

    public OrderListResult(String userId, List<OrderSummary> orders) {
        this.userId = userId == null ? "" : userId;
        this.orders = orders == null ? List.of() : List.copyOf(orders);
    }

    /** 将远程 MCP JSON Map 恢复为强类型列表契约。 */
    public static OrderListResult fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String userId = text(map, "user_id", "userId");
        Object rawOrders = first(map, "orders");
        List<OrderSummary> orders = new ArrayList<>();
        if (rawOrders instanceof List<?> rows) {
            for (Object row : rows) {
                OrderSummary order = row instanceof OrderSummary summary
                        ? summary
                        : OrderSummary.fromMap(row);
                if (order != null) {
                    orders.add(order);
                }
            }
        }
        return new OrderListResult(userId, orders);
    }

    public String getUserId() {
        return userId;
    }

    public int getCount() {
        return orders.size();
    }

    public List<OrderSummary> getOrders() {
        return orders;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String text(Map<?, ?> map, String... keys) {
        Object value = first(map, keys);
        return value == null ? "" : value.toString();
    }
}
