package com.smartcs.smart_cs_agent.handoff.sla;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * SLA 结果回调载荷（契约“结果回传接口”一节的完整结构）。
 *
 * <p>字段只声明 Agent 侧实际消费的部分；未知字段忽略，便于 SLA 后续扩展协议。
 * 语义校验（schemaVersion、outcome、坐标一致性等）集中在 {@link #validate(String)}，
 * 控制器先做格式校验，租户绑定校验通过后才进入 Store。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlaResultCallbackPayload(
        Integer schemaVersion,
        String eventId,
        String eventType,
        String tenantId,
        Long workItemId,
        String requestId,
        String channel,
        String sellerAccountId,
        String itemId,
        String conversationId,
        String buyerId,
        Result result
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String EVENT_TYPE = "WORK_ITEM_RESULT_RECORDED";

    /** 契约 v1 的结果范围只有会话级。 */
    public static final String SCOPE_CONVERSATION = "CONVERSATION";

    public static final Set<String> SUPPORTED_OUTCOMES = Set.of(
            "CONFIRMED",
            "UNABLE_TO_VERIFY",
            "WITHDRAWN"
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            Integer version,
            String scope,
            String outcome,
            String content,
            String recordedBy,
            String recordedAt
    ) {
    }

    /**
     * 契约级格式校验。返回 null 表示合法；否则返回 400 INVALID_REQUEST 的原因（不回传给 SLA，
     * 只进日志与内部异常，避免泄露校验细节）。
     */
    public String validateFormat() {
        if (schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
            return "schemaVersion must be 1";
        }
        if (eventId == null || !SlaV1RequestIds.isValid(eventId)) {
            return "eventId is missing or malformed";
        }
        if (!EVENT_TYPE.equals(eventType)) {
            return "eventType must be WORK_ITEM_RESULT_RECORDED";
        }
        if (isBlank(tenantId) || overLength(tenantId, 128)) {
            return "tenantId is missing or too long";
        }
        if (workItemId == null || workItemId <= 0) {
            return "workItemId must be a positive number";
        }
        if (!SlaV1RequestIds.isValid(requestId)) {
            return "requestId is missing or malformed";
        }
        if (isBlank(channel) || overLength(channel, 32)) {
            return "channel is missing or too long";
        }
        if (isBlank(sellerAccountId) || overLength(sellerAccountId, 128)) {
            return "sellerAccountId is missing or too long";
        }
        if (isBlank(itemId) || overLength(itemId, 128)) {
            return "itemId is missing or too long";
        }
        if (isBlank(conversationId) || overLength(conversationId, 128)) {
            return "conversationId is missing or too long";
        }
        if (isBlank(buyerId) || overLength(buyerId, 128)) {
            return "buyerId is missing or too long";
        }
        if (result == null) {
            return "result is required";
        }
        if (result.version() == null || result.version() < 1) {
            return "result.version must be a positive integer";
        }
        if (!SCOPE_CONVERSATION.equals(result.scope())) {
            return "result.scope must be CONVERSATION";
        }
        if (result.outcome() == null
                || !SUPPORTED_OUTCOMES.contains(result.outcome().toUpperCase(Locale.ROOT))) {
            return "result.outcome is not supported";
        }
        if (isBlank(result.content()) || overLength(result.content(), 2000)) {
            return "result.content is missing or too long";
        }
        if (isBlank(result.recordedBy()) || overLength(result.recordedBy(), 128)) {
            return "result.recordedBy is missing or too long";
        }
        if (result.recordedAt() == null) {
            return "result.recordedAt is required";
        }
        try {
            Instant.parse(result.recordedAt());
        } catch (DateTimeParseException malformedTimestamp) {
            return "result.recordedAt must be an UTC ISO-8601 instant";
        }
        return null;
    }

    /** 结果语义等价比较：同版本下除 recordedAt 以外的实质字段一致视为同一结果。 */
    public boolean sameResultContent(SlaResultCallbackPayload other) {
        if (other == null || other.result == null) {
            return false;
        }
        return Objects.equals(result.outcome(), other.result.outcome())
                && Objects.equals(result.scope(), other.result.scope())
                && Objects.equals(result.content(), other.result.content())
                && Objects.equals(result.recordedBy(), other.result.recordedBy());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean overLength(String value, int maxLength) {
        // 契约：身份字段前后空白视为非法，因此这里不做 trim 后度量。
        return value != null && value.length() > maxLength;
    }
}
