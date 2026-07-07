package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 退换货相关工具类
 *
 * 【功能】
 * 1. applyReturn - 申请退换货
 * 2. queryReturnStatus - 查询退换货进度
 *
 * 【业务逻辑】
 * - 申请退换货时需要验证订单是否存在
 * - 同一个订单不能重复申请
 * - 自动生成退换单号
 *
 * 【依赖的 Repository】
 * - ReturnRepository: 退换货记录
 * - OrderRepository: 验证订单
 * - ProductRepository: 查询商品名
 */
@Component
public class ReturnTools {

    private final ReturnRepository returnRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    /**
     * 构造函数 - 注入三个 Repository
     *
     * 【为什么需要三个？】
     * - ReturnRepository: 保存退换货记录
     * - OrderRepository: 验证订单是否存在
     * - ProductRepository: 查询商品名称（给用户看）
     */
    public ReturnTools(ReturnRepository returnRepository,
                       OrderRepository orderRepository,
                       ProductRepository productRepository) {
        this.returnRepository = returnRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * 申请退换货
     *
     * 【使用场景】
     * - "我想退 ORD-004 这个订单，质量有问题"
     * - "ORD-002 可以退货吗？不想要了"
     *
     * @param orderId 订单号
     * @param reason 退换货原因
     * @return 申请结果（成功/失败）
     */
    @Tool("apply for product return, need order id and reason")
    public String applyReturn(
            @P("order id") String orderId,
            @P("return reason") String reason) {

        // 1. 验证订单是否存在
        Order order = orderRepository.findByOrderId(orderId.toUpperCase());
        if (order == null) {
            return "Order " + orderId + " not found, cannot apply for return.";
        }

        // 2. 检查是否已有退换货申请（防止重复申请）
        ReturnRecord existing = returnRepository.findByOrderId(orderId.toUpperCase());
        if (existing != null) {
            return "Order " + orderId + " already has a return application, return id: " +
                   existing.getReturnId() + ", status: " + existing.getStatus();
        }

        // 3. 生成退换单号
        String returnId = returnRepository.generateReturnId();

        // 4. 查询商品名称
        Product product = productRepository.findByProductId(order.getProductId());
        String productName = (product != null) ? product.getName() : "Unknown Product";

        // 5. 创建并保存退换货记录
        ReturnRecord record = new ReturnRecord(returnId, orderId.toUpperCase(), reason, "Under Review");
        returnRepository.save(record);

        // 6. 返回申请结果
        return "Return application submitted:\n" +
               "- Return ID: " + returnId + "\n" +
               "- Order ID: " + orderId + "\n" +
               "- Product: " + productName + "\n" +
               "- Reason: " + reason + "\n" +
               "- Status: Under Review (expected 1-2 business days)";
    }

    /**
     * 提交退换货申请，并返回结构化结果。
     *
     * 注意：这个方法会真正写入 ReturnRepository。
     * 所以 ToolRegistry 会调用它，而不是先调用 applyReturn(...) 再单独查数据，
     * 避免一次用户请求创建两条退货申请。
     */
    public StructuredToolOutput applyReturnStructured(String orderId, String reason) {
        String normalizedOrderId = orderId == null ? "" : orderId.toUpperCase();

        Order order = orderRepository.findByOrderId(normalizedOrderId);
        if (order == null) {
            return new StructuredToolOutput(
                    "Order " + orderId + " not found, cannot apply for return.",
                    ReturnToolData.orderNotFound(orderId)
            );
        }

        ReturnRecord existing = returnRepository.findByOrderId(normalizedOrderId);
        if (existing != null) {
            Product product = productRepository.findByProductId(order.getProductId());

            String message = "Order " + orderId + " already has a return application, return id: " +
                    existing.getReturnId() + ", status: " + existing.getStatus();
            return new StructuredToolOutput(message, ReturnToolData.duplicate(existing, productName(product)));
        }

        String returnId = returnRepository.generateReturnId();
        Product product = productRepository.findByProductId(order.getProductId());
        String productName = (product != null) ? product.getName() : "Unknown Product";

        ReturnRecord record = new ReturnRecord(returnId, normalizedOrderId, reason, "Under Review");
        returnRepository.save(record);

        String message = "Return application submitted:\n" +
                "- Return ID: " + returnId + "\n" +
                "- Order ID: " + orderId + "\n" +
                "- Product: " + productName + "\n" +
                "- Reason: " + reason + "\n" +
                "- Status: Under Review (expected 1-2 business days)";
        return new StructuredToolOutput(
                message,
                ReturnToolData.submitted(record, productName, "1-2 business days")
        );
    }

    /**
     * 查询退换货进度
     *
     * 【使用场景】
     * - "我的退换货处理到哪了？"
     * - "查一下 RET-001 的状态"
     *
     * @param returnId 退换单号
     * @return 退换货进度信息
     */
    @Tool("query return application status")
    public String queryReturnStatus(
            @P("return id, format like RET-001") String returnId) {

        // 1. 查询退换货记录
        ReturnRecord record = returnRepository.findByReturnId(returnId.toUpperCase());
        if (record == null) {
            return "Return ID " + returnId + " not found, please check the ID.";
        }

        // 2. 查询关联的订单和商品信息
        Order order = orderRepository.findByOrderId(record.getOrderId());
        String productName = "Unknown Product";
        if (order != null) {
            Product product = productRepository.findByProductId(order.getProductId());
            if (product != null) {
                productName = product.getName();
            }
        }

        // 3. 返回退换货详情
        return "Return info:\n" +
               "- Return ID: " + record.getReturnId() + "\n" +
               "- Order ID: " + record.getOrderId() + "\n" +
               "- Product: " + productName + "\n" +
               "- Reason: " + record.getReason() + "\n" +
               "- Status: " + record.getStatus();
    }

    /**
     * 查询退换货结构化数据。
     */
    public ReturnToolData queryReturnStatusData(String returnId) {
        String normalizedReturnId = returnId == null ? "" : returnId.toUpperCase();
        ReturnRecord record = returnRepository.findByReturnId(normalizedReturnId);

        if (record == null) {
            return ReturnToolData.notFound(returnId);
        }

        Order order = orderRepository.findByOrderId(record.getOrderId());
        Product product = null;
        if (order != null) {
            product = productRepository.findByProductId(order.getProductId());
        }

        return ReturnToolData.queried(record, productName(product));
    }

    private String productName(Product product) {
        return product != null ? product.getName() : "Unknown Product";
    }
}
