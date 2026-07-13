package com.smartcs.smart_cs_agent.domain.returnservice;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退货申请领域服务：副作用规则的最后一道防线。
 *
 * <p>Workflow 的订单校验主要改善对话体验；真正写入前仍必须在这里重新检查。原因是未来调用者
 * 可能来自另一个 Workflow、MCP Client 或后台任务，不能假设所有上层都正确实现了安全步骤。</p>
 */
@Service
public class ReturnApplicationService {

    private final ReturnRepository returnRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    /** 不同订单可并行，同一订单的“查重 + 写入”必须原子执行。 */
    private final Map<String, Object> orderLocks = new ConcurrentHashMap<>();

    public ReturnApplicationService(ReturnRepository returnRepository,
                                    OrderRepository orderRepository,
                                    ProductRepository productRepository) {
        this.returnRepository = returnRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    public ReturnApplicationResult apply(String orderId, String reason, ExecutionContext context) {
        String normalizedOrderId = normalizeOrderId(orderId);
        if (normalizedOrderId.isBlank() || reason == null || reason.isBlank()) {
            return ReturnApplicationResult.rejected(
                    "INVALID_RETURN_ARGUMENT",
                    "订单号和退货原因不能为空。"
            );
        }

        // 防御式身份校验：即使 Gateway 配置被误改，领域写入仍不信任匿名或未验证 claim。
        if (context == null || !context.getPrincipal().isAuthenticated()) {
            return ReturnApplicationResult.rejected(
                    "AUTHENTICATION_REQUIRED",
                    "提交退货申请需要经过身份认证。"
            );
        }

        Object lock = orderLocks.computeIfAbsent(normalizedOrderId, ignored -> new Object());
        synchronized (lock) {
            Order order = orderRepository.findByOrderId(normalizedOrderId);
            if (order == null) {
                return ReturnApplicationResult.rejected(
                        "ORDER_NOT_FOUND",
                        "订单 " + normalizedOrderId + " 不存在，无法申请退货。"
                );
            }

            if (!context.getPrincipal().getUserId().equals(order.getUserId())) {
                return ReturnApplicationResult.rejected(
                        "ORDER_OWNERSHIP_MISMATCH",
                        "该订单不属于当前认证用户，无法申请退货。"
                );
            }

            // 演示规则：只有已签收订单进入退货申请。真实项目可扩展为领域状态机与政策服务。
            if (!"已签收".equals(order.getStatus())) {
                return ReturnApplicationResult.rejected(
                        "ORDER_STATUS_NOT_RETURNABLE",
                        "订单当前状态为“" + order.getStatus() + "”，暂不能提交退货申请。"
                );
            }

            ReturnRecord existing = returnRepository.findByOrderId(normalizedOrderId);
            Product product = productRepository.findByProductId(order.getProductId());
            String productName = product == null ? "Unknown Product" : product.getName();
            if (existing != null) {
                return ReturnApplicationResult.rejected(
                        "DUPLICATE_RETURN_APPLICATION",
                        "订单 " + normalizedOrderId + " 已有退货申请，退货单号：" + existing.getReturnId()
                );
            }

            String returnId = returnRepository.generateReturnId();
            ReturnRecord record = new ReturnRecord(
                    returnId,
                    normalizedOrderId,
                    reason.trim(),
                    "Under Review"
            );
            returnRepository.save(record);

            String expectedReviewTime = "1-2 business days";
            String message = "Return application submitted:\n"
                    + "- Return ID: " + returnId + "\n"
                    + "- Order ID: " + normalizedOrderId + "\n"
                    + "- Product: " + productName + "\n"
                    + "- Reason: " + reason.trim() + "\n"
                    + "- Status: Under Review (expected 1-2 business days)";
            return ReturnApplicationResult.submitted(
                    message,
                    record,
                    productName,
                    expectedReviewTime
            );
        }
    }

    private String normalizeOrderId(String orderId) {
        return orderId == null ? "" : orderId.trim().toUpperCase(Locale.ROOT);
    }
}
