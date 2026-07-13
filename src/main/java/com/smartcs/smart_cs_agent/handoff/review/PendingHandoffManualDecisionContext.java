package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.security.SensitiveTextRedactor;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一次人工处置共有的可信上下文。
 *
 * <p>Controller 将来只负责认证、解析 DTO，然后构造这个对象；Store 不再分别接收一串容易调换顺序的
 * {@code String}。{@code tenantId + outboxId + expectedVersion} 共同限定被修改的记录，actor 与授权信息则
 * 为同一事务中生成的审计事件提供依据。</p>
 */
public record PendingHandoffManualDecisionContext(
        String tenantId,
        String outboxId,
        long expectedVersion,
        String actorId,
        PendingHandoffReviewAuthority authorityUsed,
        String reasonCode,
        String decisionNote,
        PendingHandoffEvidenceSource evidenceSource
) {

    static final int TENANT_ID_MAX_CODE_POINTS = 64;
    static final int OUTBOX_ID_MAX_CODE_POINTS = 64;
    static final int ACTOR_ID_MAX_CODE_POINTS = 128;
    static final int REASON_CODE_MAX_CODE_POINTS = 64;
    static final int DECISION_NOTE_MAX_CODE_POINTS = 256;

    private static final Pattern REASON_SEPARATOR = Pattern.compile("[\\s-]+");
    private static final Pattern CANONICAL_REASON_CODE = Pattern.compile(
            "[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*"
    );

    public PendingHandoffManualDecisionContext {
        tenantId = requireTrimmedText(
                tenantId,
                "tenantId",
                TENANT_ID_MAX_CODE_POINTS
        );
        outboxId = requireTrimmedText(
                outboxId,
                "outboxId",
                OUTBOX_ID_MAX_CODE_POINTS
        );
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }
        actorId = requireTrimmedText(
                actorId,
                "actorId",
                ACTOR_ID_MAX_CODE_POINTS
        );
        Objects.requireNonNull(authorityUsed, "authorityUsed cannot be null");
        reasonCode = normalizeReasonCode(reasonCode);

        // 审计备注可能来自管理员自由输入。必须在进入任何 Store 前先脱敏、压成单行并按 Unicode
        // code point 截断；这样 emoji 等代理对不会被从中间切坏，Memory/JDBC 也不会保存不同内容。
        decisionNote = SensitiveTextRedactor.sanitizeSingleLine(
                decisionNote,
                DECISION_NOTE_MAX_CODE_POINTS
        );
        Objects.requireNonNull(evidenceSource, "evidenceSource cannot be null");
    }

    static String normalizeReasonCode(String reasonCode) {
        String canonical = requireTrimmedText(
                reasonCode,
                "reasonCode",
                REASON_CODE_MAX_CODE_POINTS
        ).toUpperCase(Locale.ROOT);
        canonical = REASON_SEPARATOR.matcher(canonical).replaceAll("_");
        // 少数字符在大写转换后会展开为多个字符（例如德语 sharp-s）。长度必须在规范化后再检查一次，
        // 否则 Java 对象可能通过校验，写入 VARCHAR(64) 时才由数据库失败或截断。
        if (canonical.codePointCount(0, canonical.length()) > REASON_CODE_MAX_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "reasonCode cannot exceed " + REASON_CODE_MAX_CODE_POINTS + " code points"
            );
        }
        if (!CANONICAL_REASON_CODE.matcher(canonical).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must contain only uppercase letters, numbers and single underscores"
            );
        }
        return canonical;
    }

    static String requireTrimmedText(String value, String field, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > maxCodePoints) {
            throw new IllegalArgumentException(
                    field + " cannot exceed " + maxCodePoints + " code points"
            );
        }
        return trimmed;
    }

    /**
     * record 默认会把八个字段全部拼进日志，其中包含租户、记录主键、管理员账号和自由备注。
     * 上下文只需要用版本、权限和结构化原因排障，因此这里明确省略所有身份坐标与自由文本。
     */
    @Override
    public String toString() {
        return "PendingHandoffManualDecisionContext["
                + "expectedVersion=" + expectedVersion
                + ", authorityUsed=" + authorityUsed
                + ", reasonCode=" + reasonCode
                + ", evidenceSource=" + evidenceSource
                + ']';
    }
}
