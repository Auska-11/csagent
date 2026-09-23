package com.smartcs.smart_cs_agent.handoff.sla;

/**
 * 回调被协议性拒绝：携带 HTTP 状态与固定 message 代码。
 *
 * <p>契约映射：404 NOT_FOUND（找不到关联）、409 EVENT_PAYLOAD_CONFLICT（同 eventId 异参）、
 * 409 VERSION_CONTENT_CONFLICT（同版本异内容）、409 ASSOCIATION_MISMATCH（坐标不符）。
 * 这些响应对 SLA 是不可重试的失败，会进入人工检查，因此代码必须可对账。</p>
 */
public class SlaResultCallbackRejectedException extends RuntimeException {

    private final int status;
    private final String code;

    public SlaResultCallbackRejectedException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
