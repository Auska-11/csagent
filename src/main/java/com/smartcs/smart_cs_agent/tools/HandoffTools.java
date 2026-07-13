package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.handoff.outbox.InMemoryPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffPreparation;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 人工转接工具。
 *
 * 【当前做什么？】
 * 1. 默认 memory 模式：生成一个本地模拟工单号，保证 Agent 项目可以单独运行。
 * 2. after-sales-http 模式：调用桌面 demo 项目的售后工单平台，创建真实工单。
 *
 * 【为什么仍然要做成工具？】
 * 因为真实项目里转人工通常会调用工单系统、IM 系统或 CRM 系统。
 * 先做成工具边界，后续替换真实系统时，不需要改 Skill 和 Runtime 的核心流程。
 */
@Component
public class HandoffTools {

    /** 简单递增序号，用于生成演示用工单号 */
    private final AtomicInteger sequence = new AtomicInteger(1000);

    private final HandoffProperties properties;
    private final AfterSalesTicketClient afterSalesTicketClient;
    private final PendingHandoffStore pendingHandoffStore;

    /**
     * 测试和手动 new 对象时使用的构造函数。
     *
     * 保留它是为了不破坏已有单元测试：如果没有 Spring 注入配置，就继续使用 memory 模式。
     */
    public HandoffTools() {
        this(new HandoffProperties(), null, new InMemoryPendingHandoffStore());
    }

    /**
     * 单元测试和其他手动构造场景使用内存 Outbox，避免每个旧测试都必须了解持久化细节。
     */
    public HandoffTools(HandoffProperties properties, AfterSalesTicketClient afterSalesTicketClient) {
        this(properties, afterSalesTicketClient, new InMemoryPendingHandoffStore());
    }

    /**
     * Spring 运行时使用的构造函数。
     *
     * <p>PendingHandoffStore 会跟随 smartcs.persistence.mode 自动选择 memory 或 jdbc 实现。</p>
     */
    @Autowired
    public HandoffTools(HandoffProperties properties,
                        AfterSalesTicketClient afterSalesTicketClient,
                        PendingHandoffStore pendingHandoffStore) {
        this.properties = properties == null ? new HandoffProperties() : properties;
        this.afterSalesTicketClient = afterSalesTicketClient;
        this.pendingHandoffStore = pendingHandoffStore == null
                ? new InMemoryPendingHandoffStore()
                : pendingHandoffStore;
    }

    /**
     * 创建人工转接工单。
     *
     * @param userId 用户 ID
     * @param conversationId 会话 ID
     * @param reason 转人工原因
     * @param summary 给人工看的摘要
     * @return 工单结构化信息
     */
    public HandoffTicketData createHandoffTicket(String userId,
                                                 String conversationId,
                                                 String reason,
                                                 String summary) {
        return createHandoffTicket(userId, conversationId, reason, summary, null);
    }

    /**
     * 创建人工转接工单，并使用上层 Capability 的操作幂等键隔离不同业务请求。
     *
     * <p>同一会话可能先后发生两次投诉，仅使用 conversationId + reason 会把它们错误合并。
     * operationIdempotencyKey 由服务端执行上下文提供，使每个已确认的副作用请求都有独立身份。</p>
     */
    public HandoffTicketData createHandoffTicket(String userId,
                                                 String conversationId,
                                                 String reason,
                                                 String summary,
                                                 String operationIdempotencyKey) {
        // 这是尚未携带 ExecutionContext 的旧 ToolRegistry 兼容入口。memory 模式没有外部租户副作用，
        // 可以继续使用默认演示租户；HTTP 模式必须 fail-closed，不能猜一个租户后写入真实售后平台。
        if (properties.isAfterSalesHttpMode()) {
            throw new HandoffTicketCreationException(
                    "Tenant-aware handoff entry point is required for after-sales HTTP mode"
            );
        }
        return createHandoffTicketForTenant(
                ConversationKey.DEFAULT_TENANT_ID,
                userId,
                conversationId,
                reason,
                summary,
                operationIdempotencyKey
        );
    }

    /**
     * 创建人工转接工单的生产入口。
     *
     * <p>tenantId、userId 和 conversationId 都由服务端执行上下文提供，不允许模型在工具参数中
     * 自由指定。完整参数看起来稍多，但它明确展示了外部副作用所依赖的安全上下文。</p>
     */
    public HandoffTicketData createHandoffTicketForTenant(String tenantId,
                                                          String userId,
                                                          String conversationId,
                                                          String reason,
                                                          String summary,
                                                          String operationIdempotencyKey) {
        requireText(tenantId, "tenantId");
        if (properties.isAfterSalesHttpMode()) {
            if (afterSalesTicketClient == null) {
                // 配置明确要求真实售后平台时，缺少 Client 必须失败，不能悄悄伪造一张 memory 工单。
                throw new HandoffTicketCreationException(
                        "After-sales ticket client is required in after-sales HTTP mode"
                );
            }
            return createAfterSalesTicket(
                    tenantId.trim(),
                    userId,
                    conversationId,
                    reason,
                    summary,
                    operationIdempotencyKey
            );
        }

        return createMemoryTicket(userId, conversationId, reason, summary, "WAITING_HUMAN_AGENT");
    }

    private HandoffTicketData createAfterSalesTicket(String tenantId,
                                                     String userId,
                                                     String conversationId,
                                                     String reason,
                                                     String summary,
                                                     String operationIdempotencyKey) {
        AfterSalesTicketCreateRequest request = new AfterSalesTicketCreateRequest(
                buildTitle(reason, summary),
                buildDescription(userId, conversationId, reason, summary),
                firstNonBlank(userId, "UNKNOWN_USER"),
                inferProductLine(reason, summary),
                inferIssueType(reason, summary),
                inferPriority(reason, summary),
                buildIdempotencyKey(
                        tenantId,
                        userId,
                        conversationId,
                        reason,
                        summary,
                        operationIdempotencyKey
                )
        );

        PendingHandoffPreparation preparation;
        try {
            // Outbox 必须先于外部副作用写入。即使进程在下一行之前退出，重启后也能看到 PREPARED，
            // 而不是在没有任何证据的情况下重新创建一张工单。
            preparation = pendingHandoffStore.prepare(new PendingHandoffCommand(
                    tenantId,
                    userId,
                    conversationId,
                    reason,
                    summary,
                    request
            ));
        } catch (RuntimeException persistenceFailure) {
            // 无法建立可靠记录时必须 fail-closed，绝不能绕过 Outbox 直接调用远端副作用。
            throw new HandoffTicketCreationException(
                    "Pending handoff request could not be prepared",
                    persistenceFailure
            );
        }

        PendingHandoffRecord preparedRecord = preparation.record();
        if (!preparation.created()) {
            if (preparedRecord.status() == PendingHandoffStatus.CONFIRMED
                    && preparedRecord.confirmedResult() != null) {
                // Capability 幂等结果可能尚未落库，但 Outbox 已经保存成功结果；直接回放即可。
                return toTicketData(
                        preparedRecord.confirmedResult(),
                        userId,
                        conversationId,
                        reason,
                        summary
                );
            }
            // 已有 PREPARED/RESULT_UNKNOWN 表示上一次执行可能已经触发远端副作用。
            // 同步请求不能凭猜测再调用一次，后续由核对/补建执行器使用原幂等键推进。
            throw new HandoffTicketCreationException(
                    "Remote handoff is awaiting reconciliation"
            );
        }

        try {
            AfterSalesTicketCreateResult result = afterSalesTicketClient.createTicket(tenantId, request);
            PendingHandoffRecord confirmed;
            try {
                confirmed = pendingHandoffStore.confirm(
                        preparedRecord.key(),
                        result,
                        preparedRecord.version()
                );
            } catch (RuntimeException persistenceFailure) {
                // 远端已经明确成功，但本地无法证明结果已持久化时，也不能向上返回成功。
                // 保守返回技术失败，避免 Capability 层保存一个无法从 Outbox 恢复的成功结果。
                throw new HandoffTicketCreationException(
                        "Remote handoff succeeded but confirmation could not be persisted",
                        persistenceFailure
                );
            }
            return toTicketData(
                    confirmed.confirmedResult(),
                    userId,
                    conversationId,
                    reason,
                    summary
            );
        } catch (RuntimeException e) {
            if (e instanceof HandoffTicketCreationException) {
                throw e;
            }
            // 超时只表示“客户端没有拿到结果”，远端可能已经创建了工单。
            // PREPARED 已在调用前写入，这里只需要把记录推进为 RESULT_UNKNOWN。
            try {
                PendingHandoffRecord unknown = pendingHandoffStore.markResultUnknown(
                        preparedRecord.key(),
                        e.getClass().getSimpleName(),
                        preparedRecord.version()
                );
                if (unknown.status() == PendingHandoffStatus.CONFIRMED
                        && unknown.confirmedResult() != null) {
                    // 极少数并发场景下，当前线程超时的同时核对执行者拿到了成功结果。
                    return toTicketData(unknown.confirmedResult(), userId, conversationId, reason, summary);
                }
            } catch (RuntimeException persistenceFailure) {
                // 外部结果未知且 Outbox 也写入失败，是更高优先级的可靠性故障。
                // 仍统一包装成安全异常，不把 SQL、连接地址或原始 HTTP 错误传给用户。
                persistenceFailure.addSuppressed(e);
                throw new HandoffTicketCreationException(
                        "Remote handoff result and pending record could not be confirmed",
                        persistenceFailure
                );
            }

            // 抛出专用异常后，Gateway 会返回技术失败；同一个幂等键会稳定回放该失败，不会立即重复请求远端。
            throw new HandoffTicketCreationException(
                    "Remote handoff result could not be confirmed",
                    e
            );
        }
    }

    private HandoffTicketData toTicketData(AfterSalesTicketCreateResult result,
                                           String userId,
                                           String conversationId,
                                           String reason,
                                           String summary) {
        if (result == null) {
            throw new HandoffTicketCreationException("Confirmed handoff result is missing");
        }
        return new HandoffTicketData(
                result.ticketId(),
                userId,
                conversationId,
                reason,
                summary,
                result.status(),
                result.createdAt()
        );
    }

    private HandoffTicketData createMemoryTicket(String userId,
                                                String conversationId,
                                                String reason,
                                                String summary,
                                                String status) {
        String ticketId = "HD-" + sequence.incrementAndGet();

        return new HandoffTicketData(
                ticketId,
                userId,
                conversationId,
                reason,
                summary,
                status,
                LocalDateTime.now().toString()
        );
    }

    /**
     * 把工单结构整理成适合 Trace 和 Prompt 阅读的文本。
     */
    public String formatTicket(HandoffTicketData ticket) {
        return "已创建人工转接工单：\n" +
                "工单号：" + ticket.getTicketId() + "\n" +
                "用户：" + ticket.getUserId() + "\n" +
                "会话：" + ticket.getConversationId() + "\n" +
                "原因：" + ticket.getReason() + "\n" +
                "状态：" + ticket.getStatus() + "\n" +
                "摘要：" + ticket.getSummary();
    }

    private String buildTitle(String reason, String summary) {
        String source = firstNonBlank(summary, reason, "客服问题");
        return limit("Agent 转人工：" + compact(source), 120);
    }

    private String buildDescription(String userId,
                                    String conversationId,
                                    String reason,
                                    String summary) {
        String description = "用户ID：" + firstNonBlank(userId, "UNKNOWN_USER") + "\n" +
                "会话ID：" + firstNonBlank(conversationId, "UNKNOWN_CONVERSATION") + "\n" +
                "转人工原因：" + firstNonBlank(reason, "UNKNOWN_REASON") + "\n" +
                "Agent 摘要：" + firstNonBlank(summary, "用户请求人工客服继续处理。");
        return limit(description, 2000);
    }

    private String inferPriority(String reason, String summary) {
        String text = normalize(reason + " " + summary);
        if (containsAny(text, "投诉", "赔偿", "维权", "差评", "客服经理", "严重")) {
            return "URGENT";
        }
        if (containsAny(text, "着急", "尽快", "催", "无法使用", "不能用", "坏了", "故障", "影响使用")) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private String inferProductLine(String reason, String summary) {
        String text = normalize(reason + " " + summary);
        if (containsAny(text, "退货", "退款", "换货", "售后")) {
            return "return";
        }
        if (containsAny(text, "蓝牙", "耳机")) {
            return "bluetooth";
        }
        if (containsAny(text, "手表")) {
            return "wearable";
        }
        if (containsAny(text, "充电器", "数据线")) {
            return "accessory";
        }
        if (containsAny(text, "订单", "物流", "快递", "发货", "签收")) {
            return "order";
        }
        if (containsAny(text, "发票", "开票")) {
            return "invoice";
        }
        return "general";
    }

    private String inferIssueType(String reason, String summary) {
        String text = normalize(reason + " " + summary);
        if (containsAny(text, "投诉", "差评", "客服经理")) {
            return "complaint";
        }
        if (containsAny(text, "赔偿", "维权", "纠纷")) {
            return "dispute";
        }
        if (containsAny(text, "退货", "退款", "换货")) {
            return "return";
        }
        if (containsAny(text, "物流", "快递", "发货", "签收")) {
            return "logistics";
        }
        if (containsAny(text, "发票", "开票")) {
            return "invoice";
        }
        if (containsAny(text, "故障", "坏了", "不能用", "无法使用", "质量问题")) {
            return "quality";
        }
        return "handoff";
    }

    private String buildIdempotencyKey(String tenantId,
                                       String userId,
                                       String conversationId,
                                       String reason,
                                       String summary,
                                       String operationIdempotencyKey) {
        // 外部 Header 不直接暴露 userId/conversationId。完整 SHA-256 仍能稳定关联同一逻辑操作，
        // 并避免不同用户恰好使用相同客户端 key 时在售后平台发生碰撞。
        String operation = firstNonBlank(operationIdempotencyKey, summary, "handoff");
        String source = hashSegment(tenantId)
                + hashSegment(userId)
                + hashSegment(conversationId)
                + hashSegment(reason)
                + hashSegment(operation);
        try {
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8))
            );
            return "agent-handoff-" + digest;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String hashSegment(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        return firstNonBlank(first, second, "");
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third;
    }

    private String compact(String text) {
        return firstNonBlank(text, "").replaceAll("\\s+", " ").trim();
    }

    private String limit(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
