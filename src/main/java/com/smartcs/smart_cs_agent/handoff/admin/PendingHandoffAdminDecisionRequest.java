package com.smartcs.smart_cs_agent.handoff.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** retry、close 和 reconcile 共用的 HTTP 请求体；身份、租户、证据来源和查询结果不能由客户端填写。 */
public record PendingHandoffAdminDecisionRequest(
        @NotNull(message = "Expected version cannot be empty")
        @PositiveOrZero(message = "Expected version cannot be negative")
        Long expectedVersion,

        @NotBlank(message = "Reason code cannot be empty")
        @Size(max = 64, message = "Reason code is too long")
        @Pattern(
                regexp = "[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*",
                message = "Reason code must use uppercase letters, numbers and single underscores"
        )
        String reasonCode,

        // 入口先限制请求体大小；领域 Context 随后还会脱敏、压成单行并截到 256 Unicode 字符。
        @Size(max = 1000, message = "Decision note is too long")
        String decisionNote
) {
}
