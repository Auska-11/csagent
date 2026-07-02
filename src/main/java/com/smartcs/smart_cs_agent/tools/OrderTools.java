package com.smartcs.smart_cs_agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单相关工具类
 *
 * 学习要点：
 * 1. @Tool 注解 - 告诉 LLM 这个工具能做什么
 * 2. @P 注解 - 告诉 LLM 参数的含义
 * 3. LLM 会根据用户意图，自动决定调用哪个工具
 *
 * 当前使用模拟数据，以后接入真实系统时只需修改方法内部实现
 */
@Component
public class OrderTools {

    /**
     * 模拟订单数据库
     * 结构：订单号 -> 订单信息（状态、物流单号、商品名称）
     */
    private static final Map<String, Map<String, String>> ORDER_DB = new HashMap<>() {{
        put("ORD-001", Map.of(
            "status", "已发货",
            "trackingNo", "SF1234567890",
            "item", "蓝牙耳机 Pro Max"
        ));
        put("ORD-002", Map.of(
            "status", "待发货",
            "trackingNo", "",
            "item", "智能手表 S2"
        ));
        put("ORD-003", Map.of(
            "status", "已签收",
            "trackingNo", "YT9876543210",
            "item", "无线充电器"
        ));
    }};

    /**
     * 模拟产品数据库
     * 结构：产品名称 -> 产品信息
     */
    private static final Map<String, Map<String, String>> PRODUCT_DB = new HashMap<>() {{
        put("蓝牙耳机", Map.of(
            "name", "蓝牙耳机 Pro Max",
            "price", "599元",
            "stock", "有货",
            "desc", "支持主动降噪，续航30小时"
        ));
        put("智能手表", Map.of(
            "name", "智能手表 S2",
            "price", "1299元",
            "stock", "有货",
            "desc", "14天超长续航，5ATM防水"
        ));
    }};

    /**
     * 查询订单状态
     *
     * LLM 会自动识别用户意图，当用户询问订单相关问题时调用此方法
     *
     * @param orderId 订单号，如 ORD-001
     * @return 订单状态信息
     */
    @Tool("查询订单状态，返回订单的当前状态、物流信息和商品名称")
    public String queryOrderStatus(
            @P("订单号，格式如 ORD-001") String orderId) {

        // 从模拟数据库查询订单
        Map<String, String> order = ORDER_DB.get(orderId.toUpperCase());

        if (order == null) {
            return "未找到订单 " + orderId + "，请检查订单号是否正确。";
        }

        // 拼接返回结果
        StringBuilder result = new StringBuilder();
        result.append("订单 ").append(orderId).append(" 信息：\n");
        result.append("- 商品：").append(order.get("item")).append("\n");
        result.append("- 状态：").append(order.get("status")).append("\n");

        // 只有已发货时才显示物流单号
        String trackingNo = order.get("trackingNo");
        if (trackingNo != null && !trackingNo.isEmpty()) {
            result.append("- 物流单号：").append(trackingNo);
        }

        return result.toString();
    }

    /**
     * 申请退换货
     *
     * @param orderId 订单号
     * @param reason 退换货原因
     * @return 退换货申请结果
     */
    @Tool("为用户申请退换货服务，需要提供订单号和原因")
    public String requestReturn(
            @P("订单号") String orderId,
            @P("退换货原因") String reason) {

        // 检查订单是否存在
        Map<String, String> order = ORDER_DB.get(orderId.toUpperCase());
        if (order == null) {
            return "未找到订单 " + orderId + "，无法申请退换货。";
        }

        // 模拟创建退换货工单
        String returnId = "RET-" + orderId.replace("ORD-", "");

        return "退换货申请已提交：\n" +
               "- 退换货单号：" + returnId + "\n" +
               "- 订单号：" + orderId + "\n" +
               "- 商品：" + order.get("item") + "\n" +
               "- 原因：" + reason + "\n" +
               "- 状态：审核中（预计1-2个工作日内处理）";
    }

    /**
     * 查询产品信息
     *
     * @param productName 产品名称关键词
     * @return 产品详细信息
     */
    @Tool("查询产品详细信息，包括价格、库存和描述")
    public String queryProductInfo(
            @P("产品名称，如 蓝牙耳机、智能手表") String productName) {

        // 模糊匹配产品
        for (Map.Entry<String, Map<String, String>> entry : PRODUCT_DB.entrySet()) {
            if (productName.contains(entry.getKey())) {
                Map<String, String> product = entry.getValue();
                return "产品信息：\n" +
                       "- 名称：" + product.get("name") + "\n" +
                       "- 价格：" + product.get("price") + "\n" +
                       "- 库存：" + product.get("stock") + "\n" +
                       "- 描述：" + product.get("desc");
            }
        }

        return "未找到产品：" + productName + "，请确认产品名称。";
    }
}