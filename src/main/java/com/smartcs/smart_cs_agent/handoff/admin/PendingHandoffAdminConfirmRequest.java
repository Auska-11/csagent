package com.smartcs.smart_cs_agent.handoff.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 人工确认请求；evidenceSource 由服务端固定为 ADMIN_PROVIDED。 */
public record PendingHandoffAdminConfirmRequest(
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

        @Size(max = 1000, message = "Decision note is too long")
        String decisionNote,

        @NotNull(message = "Confirmed ticket result cannot be empty")
        @Valid
        PendingHandoffAdminConfirmResultRequest result
) {
}
