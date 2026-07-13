package com.smartcs.smart_cs_agent.handoff.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewCursor;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Objects;

/**
 * 在 HTTP 不透明文本和 Store 结构化 keyset 游标之间转换。
 *
 * <p>游标不是加密数据，所以载荷只保存排序坐标；同时绑定用途和筛选条件，防止列表游标被误用到审计
 * 接口，或从一种 status/outbox 页面复制到另一页产生难以解释的结果。</p>
 */
@Component
public class PendingHandoffAdminCursorCodec {

    private static final int VERSION = 1;
    private static final int MAX_ENCODED_CHARS = 512;
    private static final int MAX_JSON_BYTES = 384;
    private static final String REVIEW_KIND = "review";
    private static final String AUDIT_KIND = "audit";

    private final ObjectMapper objectMapper;

    public PendingHandoffAdminCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    /** 把 Store 列表游标编码成绑定 status 的 Base64URL 文本；末页没有游标时返回 null。 */
    public String encodeReview(PendingHandoffReviewCursor cursor, PendingHandoffStatus status) {
        if (cursor == null) {
            return null;
        }
        Objects.requireNonNull(status, "status cannot be null");
        return encode(new CursorPayload(
                VERSION, REVIEW_KIND, status.name(), null,
                cursor.updatedAt(), cursor.outboxId()
        ));
    }

    /** 解码列表游标；损坏、错版本、错用途或从其他 status 复制来的文本都按安全 400 处理。 */
    public PendingHandoffReviewCursor decodeReview(String encoded, PendingHandoffStatus expectedStatus) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        Objects.requireNonNull(expectedStatus, "expectedStatus cannot be null");
        CursorPayload payload = decode(encoded);
        if (payload.version() != VERSION
                || !REVIEW_KIND.equals(payload.kind())
                || !expectedStatus.name().equals(payload.status())
                || payload.resourceOutboxId() != null) {
            throw new PendingHandoffAdminCursorException();
        }
        try {
            return new PendingHandoffReviewCursor(payload.sortTime(), payload.sortId());
        } catch (RuntimeException invalidValue) {
            throw new PendingHandoffAdminCursorException();
        }
    }

    /** 把审计游标编码成绑定 outboxId 的文本，防止时间线游标跨资源误用。 */
    public String encodeAudit(PendingHandoffReviewAuditCursor cursor, String resourceOutboxId) {
        if (cursor == null) {
            return null;
        }
        String boundedOutboxId = requireId(resourceOutboxId);
        return encode(new CursorPayload(
                VERSION, AUDIT_KIND, null, boundedOutboxId,
                cursor.occurredAt(), cursor.auditId()
        ));
    }

    /** 解码审计游标；只有用途、版本和目标 outboxId 全部匹配时才返回结构化坐标。 */
    public PendingHandoffReviewAuditCursor decodeAudit(String encoded, String expectedOutboxId) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String boundedOutboxId = requireId(expectedOutboxId);
        CursorPayload payload = decode(encoded);
        if (payload.version() != VERSION
                || !AUDIT_KIND.equals(payload.kind())
                || payload.status() != null
                || !boundedOutboxId.equals(payload.resourceOutboxId())) {
            throw new PendingHandoffAdminCursorException();
        }
        try {
            return new PendingHandoffReviewAuditCursor(payload.sortTime(), payload.sortId());
        } catch (RuntimeException invalidValue) {
            throw new PendingHandoffAdminCursorException();
        }
    }

    private String encode(CursorPayload payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            if (json.length > MAX_JSON_BYTES) {
                throw new PendingHandoffAdminCursorException();
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (PendingHandoffAdminCursorException known) {
            throw known;
        } catch (Exception serializationFailure) {
            throw new PendingHandoffAdminCursorException();
        }
    }

    private CursorPayload decode(String encoded) {
        if (encoded.length() > MAX_ENCODED_CHARS) {
            throw new PendingHandoffAdminCursorException();
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            if (json.length > MAX_JSON_BYTES) {
                throw new PendingHandoffAdminCursorException();
            }
            CursorPayload payload = objectMapper.readValue(json, CursorPayload.class);
            if (payload == null || payload.sortTime() < 0) {
                throw new PendingHandoffAdminCursorException();
            }
            requireId(payload.sortId());
            return payload;
        } catch (PendingHandoffAdminCursorException known) {
            throw known;
        } catch (Exception parsingFailure) {
            // 不把 Base64/Jackson 的原始消息带到 HTTP；它可能包含攻击者提供的游标片段。
            throw new PendingHandoffAdminCursorException();
        }
    }

    private String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new PendingHandoffAdminCursorException();
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > 64) {
            throw new PendingHandoffAdminCursorException();
        }
        return trimmed;
    }

    private record CursorPayload(
            int version,
            String kind,
            String status,
            String resourceOutboxId,
            long sortTime,
            String sortId
    ) {
    }
}
