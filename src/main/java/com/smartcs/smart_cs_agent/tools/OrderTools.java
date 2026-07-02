package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单相关工具类
 *
 * 【核心知识点】
 * 1. @Component - 让 Spring 管理这个类，自动创建实例并注入到其他地方
 * 2. @Tool - 告诉 LLM "这个方法是一个可调用的工具"
 * 3. @P - 告诉 LLM "这个参数是什么意思"
 *
 * 【工作原理】
 * 当用户说 "帮我查一下订单 ORD-001" 时：
 * 1. LLM 收到用户消息
 * 2. LLM 看到所有标记了 @Tool 的方法描述
 * 3. LLM 判断应该调用 queryOrderStatus("ORD-001")
 * 4. LangChain4j 自动执行这个方法
 * 5. 把返回结果交给 LLM 生成最终回复
 *
 * 【为什么要用 Repository？】
 * - 之前版本：数据写死在 Map 里，不好维护
 * - 现在版本：通过 Repository 获取数据，解耦数据访问层
 * - 好处：以后换成真实数据库，只改 Repository，Tools 不用动
 */
@Component
public class OrderTools {

    /**
     * 注入 OrderRepository - 用于查询订单数据
     * Spring 会在创建 OrderTools 时自动注入
     */
    private final OrderRepository orderRepository;

    /**
     * 注入 ProductRepository - 用于查询商品名称
     * 因为订单里只存了 productId，需要再查一次才能拿到商品名
     */
    private final ProductRepository productRepository;

    /**
     * 构造函数注入
     *
     * 【Spring 依赖注入的三种方式】
     * 1. @Autowired 字段注入（不推荐）
     * 2. Setter 注入（不推荐）
     * 3. 构造函数注入（推荐） - 就是这里用的方式
     *
     * 推荐构造函数注入的原因：
     * - 可以声明为 final，保证不可变
     * - 必须传入，不会遗漏
     * - 方便单元测试
     */
    public OrderTools(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * 查询订单状态
     *
     * 【@Tool 注解】
     * 里面的字符串是给 LLM 看的描述，LLM 根据这段描述决定什么时候调用这个方法
     * 写好描述很重要！描述不清会导致 LLM 选错工具
     *
     * 【@P 注解】
     * 里面的字符串解释参数含义，LLM 会根据用户消息提取对应的值
     * 比如用户说 "查一下 ORD-001"，LLM 会把 "ORD-001" 传给 orderId 参数
     *
     * @param orderId 订单号，如 ORD-001
     * @return 订单状态信息（字符串）
     */
    @Tool("query order status, return order current status, logistics info and product name")
    public String queryOrderStatus(
            @P("order id, format like ORD-001") String orderId) {

        // 1. 从 Repository 查询订单（转大写避免大小写问题）
        Order order = orderRepository.findByOrderId(orderId.toUpperCase());

        // 2. 订单不存在，返回提示信息
        if (order == null) {
            return "Order " + orderId + " not found, please check the order id.";
        }

        // 3. 查询商品名称（订单里只存了 productId）
        Product product = productRepository.findByProductId(order.getProductId());
        String productName = (product != null) ? product.getName() : "Unknown Product";

        // 4. 拼接返回结果
        // 【重要】返回的字符串会直接给 LLM，LLM 再组织成自然语言回复用户
        StringBuilder result = new StringBuilder();
        result.append("Order ").append(orderId).append(" info:\n");
        result.append("- Product: ").append(productName).append("\n");
        result.append("- Status: ").append(order.getStatus()).append("\n");

        // 5. 只有已发货才显示物流单号
        String trackingNo = order.getTrackingNo();
        if (trackingNo != null && !trackingNo.isEmpty()) {
            result.append("- Tracking No: ").append(trackingNo);
        }

        return result.toString();
    }

    /**
     * 查询用户的所有订单
     *
     * 【什么时候调用？】
     * 当用户说 "我有哪些订单"、"查看我的订单" 时，LLM 会调用这个方法
     *
     * @param userId 用户ID，如 USER-001
     * @return 该用户的所有订单列表
     */
    @Tool("query all orders of a specific user")
    public String queryUserOrders(
            @P("user id, format like USER-001") String userId) {

        // 查询该用户的所有订单
        List<Order> orders = orderRepository.findByUserId(userId);

        if (orders.isEmpty()) {
            return "User " + userId + " has no orders.";
        }

        // 拼接订单列表
        StringBuilder result = new StringBuilder();
        result.append("User ").append(userId).append(" orders:\n");

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);

            // 查询每个订单对应的商品名
            Product product = productRepository.findByProductId(order.getProductId());
            String productName = (product != null) ? product.getName() : "Unknown Product";

            result.append(i + 1).append(". Order: ").append(order.getOrderId());
            result.append(" | Product: ").append(productName);
            result.append(" | Status: ").append(order.getStatus());

            String trackingNo = order.getTrackingNo();
            if (trackingNo != null && !trackingNo.isEmpty()) {
                result.append(" | Tracking: ").append(trackingNo);
            }

            // 最后一个订单不加换行
            if (i < orders.size() - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }
}