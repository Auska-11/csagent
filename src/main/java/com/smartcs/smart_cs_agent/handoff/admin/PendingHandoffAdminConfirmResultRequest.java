package com.smartcs.smart_cs_agent.handoff.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 管理员从受信任售后后台核对到的最小工单结果。 */
public record PendingHandoffAdminConfirmResultRequest(
        @NotBlank(message = "Ticket id cannot be empty")
        @Size(max = 128, message = "Ticket id is too long")
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]*",
                message = "Ticket id contains unsupported characters"
        )
        String ticketId,

        @NotBlank(message = "Ticket status cannot be empty")
        @Size(max = 64, message = "Ticket status is too long")
        @Pattern(
                regexp = "[A-Za-z][A-Za-z0-9_.-]*",
                message = "Ticket status contains unsupported characters"
        )
        String status,

        @NotBlank(message = "Ticket createdAt cannot be empty")
        @Size(max = 128, message = "Ticket createdAt is too long")
        String createdAt
) {
}
